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
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The graph itself, with no compatibility layer in front of it.
 *
 * Deliberately narrow, and section 13 of the plan says why each piece is out
 * rather than not yet in. A `pw_stream` is a node with the buffer handling
 * done, which is the whole playback path; everything this backend does not
 * offer needs the registry, which is a second interface the size of
 * `VolumeMixer` for questions `VolumeMixer` already answers over the pulse
 * protocol.
 *
 * So this is a stream in each direction and nothing else, it reports exactly
 * that, and a consumer that wants a device list or a mixer asks the libpulse
 * backend, which is still the one the selection reaches first. What this one
 * has instead is what the shim cannot carry: twenty-six channel positions
 * against eighteen, a 64-bit float, and a latency the node asks for rather than
 * one translated on its behalf.
 */
internal class PipeWireBackend private constructor(
    private val loop: PipeWireLoop,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    override val name: String = "pipewire"

    override val capabilities: Capabilities = BACKEND_CAPABILITIES

    private val closed = AtomicBoolean(false)

    private val sinks = CopyOnWriteArrayList<PipeWireSink>()
    private val sources = CopyOnWriteArrayList<PipeWireSource>()

    override fun createSink(config: SinkConfig): AudioSink {
        if (closed.get()) throw AudioException("backend is closed")
        val sink = PipeWireSink(loop, config, SINK_CAPABILITIES)
        sinks.add(sink)
        return sink
    }

    /**
     * Empty, and [Capability.DEVICE_ENUMERATION] is absent to say so.
     *
     * Listing the graph's nodes means the registry and a proxy for every global
     * on it. Section 13.8 leaves that out of the first cut on purpose: the
     * questions a consumer actually asks a device list, which are what exists
     * and how loud it is and where a stream is playing, are `VolumeMixer`'s and
     * it answers them over the pulse protocol already.
     */
    override fun devices(): List<AudioDevice> = emptyList()

    override fun defaultDevice(): AudioDevice? = null

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
        val source = PipeWireSource(loop, config, SOURCE_CAPABILITIES)
        sources.add(source)
        return source
    }

    override fun captureDevices(): List<AudioDevice> = emptyList()

    override fun defaultCaptureDevice(): AudioDevice? = null

    /** A server-side sample cache is a PulseAudio idea with no equivalent here. */
    override fun cacheSample(name: String, format: AudioFormat, pcm: ByteArray): SampleId? = null

    override fun playSample(id: SampleId, device: DeviceId?, volume: Float): Boolean = false

    /** Nothing to subscribe to without the registry, and the capability says so. */
    override fun onDevicesChanged(handler: () -> Unit): () -> Unit = {}

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        sources.forEach { runCatching { it.close() } }
        sources.clear()
        // Every stream is destroyed before the loop is stopped, and the loop is
        // stopped before the arena holding the upcall stubs is freed.
        loop.close()
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /**
         * What a sink here can do.
         *
         * No `STREAM_VOLUME`: a node's volume is a control reached through the
         * registry, which this backend does not open, so claiming it would
         * offer a slider that moves nothing.
         */
        private val SINK_CAPABILITIES = Capabilities.of(
            Capability.STREAM_IDENTITY,
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

        private val BACKEND_CAPABILITIES = SOURCE_CAPABILITIES

        /**
         * Start a loop and return the backend, or null where there is no graph.
         *
         * Null on a machine running real PulseAudio, on one with no sound
         * server, and on one where libpipewire is not on the search path. All
         * three are ordinary answers the selection falls back on.
         */
        fun connectOrNull(applicationName: String): AudioBackend? {
            val loop = PipeWireLoop.startOrNull(applicationName) ?: return null
            return runCatching {
                log.info("pipewire {} reached natively", loop.lib.version() ?: "?")
                PipeWireBackend(loop)
            }.getOrElse {
                log.debug("PipeWire backend setup failed: {}", it.message)
                loop.close()
                null
            }
        }
    }
}
