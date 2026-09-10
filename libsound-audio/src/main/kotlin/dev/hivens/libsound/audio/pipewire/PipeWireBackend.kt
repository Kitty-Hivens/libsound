package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.SampleId
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The graph itself, with no compatibility layer in front of it.
 *
 * A stream in each direction, a device list, and the events that keep it
 * current. What it has that the shim cannot carry is twenty-six channel
 * positions against eighteen, a 64-bit float, and a `node.latency` the node
 * asks for rather than one translated on its behalf.
 *
 * ## Two connections, on purpose
 *
 * Streams get one and the registry another, which is the arrangement the
 * libpulse backend and its mixer already have and for the same stated reason: a
 * registry reports every object on the machine, and that traffic has no
 * business on the connection carrying audio timing.
 *
 * The registry's is also the one that answers whether there is a graph at all.
 * Loading the library and starting a loop touches no socket, so on a machine
 * with libpipewire installed and nothing running they both succeed, and without
 * a connection to fail there is nothing to fall back from.
 *
 * ## What it does not do
 *
 * A sample cache, which is a PulseAudio idea the graph has no equivalent of,
 * and per-application capture, which needs the registry to name a stream rather
 * than a device.
 *
 * Everything past that is `VolumeMixer`'s: what else is playing, how loud, and
 * where. That interface answers those over the pulse protocol and is not
 * duplicated here.
 */
internal class PipeWireBackend private constructor(
    private val loop: PipeWireLoop,
    private val registry: PipeWireRegistry,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    override val name: String = "pipewire"

    override val capabilities: Capabilities = Capabilities(
        SOURCE_CAPABILITIES.supported + REGISTRY_CAPABILITIES,
    )

    private val closed = AtomicBoolean(false)

    /** A source's, which is the backend's own minus what only a backend can do. */
    private val sourceCapabilities: Capabilities = Capabilities(
        SOURCE_CAPABILITIES.supported + Capability.PER_STREAM_CAPTURE,
    )

    private val sinks = CopyOnWriteArrayList<PipeWireSink>()
    private val sources = CopyOnWriteArrayList<PipeWireSource>()

    override fun createSink(config: SinkConfig): AudioSink {
        if (closed.get()) throw AudioException("backend is closed")
        val sink = PipeWireSink(loop, config, SINK_CAPABILITIES)
        sinks.add(sink)
        return sink
    }

    /**
     * Every audio sink on the graph, as the registry has been told about them.
     *
     * No round trip: a global arrives with its whole property dict attached, so
     * the answer is already here and a list is a filter over what has been
     * heard rather than a call and a wait.
     */
    override fun devices(): List<AudioDevice> =
        if (closed.get()) emptyList() else registry.devices(StreamDirection.PLAYBACK)

    /**
     * What the session manager currently calls the default output, or null.
     *
     * Not a property of the graph: it is a value written into a metadata
     * object, so answering it means binding that object and listening to it,
     * which is the one proxy this backend holds. Null is still a real answer
     * and covers a graph with no session manager on it at all.
     */
    override fun defaultDevice(): AudioDevice? =
        if (closed.get()) null else registry.defaultDevice(StreamDirection.PLAYBACK)

    /**
     * The same stream with the direction reversed, which is how `pw_stream`
     * spells the mirror.
     *
     * It carries the wider format and position sets the sink does, so a
     * recorder here can ask for a 64-bit float and for a layout the
     * compatibility layer has no words for.
     */
    override fun createSource(config: SourceConfig): AudioSource {
        if (closed.get()) throw AudioException("backend is closed")
        val source = PipeWireSource(loop, config, sourceCapabilities, registry)
        sources.add(source)
        return source
    }

    override fun captureDevices(): List<AudioDevice> =
        if (closed.get()) emptyList() else registry.devices(StreamDirection.CAPTURE)

    /**
     * The default input, read out of the same metadata object.
     *
     * Null more often than [defaultDevice] is, and honestly so: a graph whose
     * only input is a sink's monitor has the default naming a node that is not
     * in this list, because a monitor is not a node of its own here.
     */
    override fun defaultCaptureDevice(): AudioDevice? =
        if (closed.get()) null else registry.defaultDevice(StreamDirection.CAPTURE)

    /** A server-side sample cache is a PulseAudio idea with no equivalent here. */
    override fun cacheSample(name: String, format: AudioFormat, pcm: ByteArray): SampleId? = null

    override fun playSample(id: SampleId, device: DeviceId?, volume: Float): Boolean = false

    /**
     * Free once the registry is listening: the same event stream that fills the
     * device list says when it changed.
     *
     * Deliberately coarse, like every other backend's: it says something moved
     * rather than what, and every consumer re-reads the list anyway.
     */
    override fun onDevicesChanged(handler: () -> Unit): () -> Unit =
        registry.onChanged(handler)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        sources.forEach { runCatching { it.close() } }
        sources.clear()
        runCatching { registry.close() }
        // Every stream is destroyed before the loop is stopped, and the loop is
        // stopped before the arena holding the upcall stubs is freed.
        loop.close()
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /** What a sink here can do. */
        private val SINK_CAPABILITIES = Capabilities.of(
            Capability.STREAM_IDENTITY,
            // A control on the node, so the desktop's mixer shows it and
            // follows it rather than the samples being scaled behind its back.
            Capability.STREAM_VOLUME,
            Capability.DEVICE_POSITION,
            Capability.UNDERRUN_COUNT,
            // The node asks the graph for a quantum and keeps it, which is the
            // lever the compatibility layer recomputes.
            Capability.LOW_LATENCY,
            // pw_time carries the graph's own share beside what this client has
            // queued, so the number is the whole path.
            Capability.TOTAL_LATENCY,
            // Positions travel in the format itself, and twenty-six of the
            // thirty-six FFmpeg names have one here.
            Capability.CHANNEL_PLACEMENT,
        )

        /**
         * A source's, which is a sink's plus the one entry a sink cannot
         * carry, because a sink is not the thing that captures.
         */
        private val SOURCE_CAPABILITIES = Capabilities(
            SINK_CAPABILITIES.supported + Capability.CAPTURE,
        )

        /** What the second connection adds on top of what a stream can do. */
        private val REGISTRY_CAPABILITIES = setOf(
            Capability.DEVICE_ENUMERATION,
            Capability.DEVICE_SELECTION,
            Capability.DEVICE_EVENTS,
            // The device's own, not a stream's, which is what the registry
            // binds each audio node to reach.
            Capability.DEVICE_VOLUME,
            // Recording one application means naming its node, and knowing
            // which node is the registry's answer.
            Capability.PER_STREAM_CAPTURE,
        )

        /**
         * Start a loop and return the backend, or null where there is no graph.
         *
         * Null on a machine running real PulseAudio, on one with no sound
         * server, and on one where libpipewire is not on the search path. All
         * three are ordinary answers the selection falls back on.
         *
         * ## The registry is the test for a graph, not an extra
         *
         * Loading the library and starting a thread loop reaches no server: a
         * machine with libpipewire installed and no graph running gets through
         * both without touching a socket, and the first thing that would have
         * found out is a sink failing to connect, by which time the selection
         * has already committed to this backend and the rung below it is gone.
         *
         * So the registry's connect is what answers whether there is a graph,
         * and a backend is handed back only when there is. An earlier version
         * treated a missing registry as survivable and reported it by
         * withholding capabilities, which was a reasonable-looking way of
         * returning a backend that could not play at all.
         */
        fun connectOrNull(applicationName: String): AudioBackend? {
            val loop = PipeWireLoop.startOrNull(applicationName) ?: return null
            return runCatching {
                val registry = PipeWireRegistry.openOrNull(applicationName)
                    ?: throw IllegalStateException("no graph answered")
                log.info("pipewire {} reached natively", loop.lib.version() ?: "?")
                PipeWireBackend(loop, registry)
            }.getOrElse {
                log.debug("no PipeWire graph reachable: {}", it.message)
                loop.close()
                null
            }
        }
    }
}
