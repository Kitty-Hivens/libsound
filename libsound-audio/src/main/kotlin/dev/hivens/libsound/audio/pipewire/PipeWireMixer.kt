package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioCard
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.LatencyProfile
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.CardId
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pulse.PulseStreamHandle
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Everyone else's audio, read off the graph rather than through the protocol in
 * front of it.
 *
 * ## Why this exists at all
 *
 * `VolumeMixers.open` answered with a libpulse mixer on every Linux machine,
 * which is fine where `pipewire-pulse` is installed and is nothing at all where
 * it is not. A machine running PipeWire without the shim is one of the two the
 * rung below `AudioBackends` exists for, and until now it got a backend that
 * played and no mixer. It also got a capability that was not honest: the native
 * backend reads each device's volume off its node, and the capability covering
 * that says the volume can be read **and set**, which on that machine nothing
 * could do.
 *
 * ## A thin layer, deliberately
 *
 * Everything here is one call into [PipeWireRegistry], which already watches the
 * graph for the backend and already knows how to write a node's parameters and
 * the session manager's metadata. What this adds is the contract's shape: the
 * bookkeeping that lets [restoreAll] put things back, and the difference between
 * one coarse "something moved" and the three events a consumer subscribes for.
 *
 * A connection of its own, for the reason the libpulse mixer takes one: a
 * subscription reporting every object on the machine has no business on the
 * socket carrying audio timing.
 *
 * ## What it does not cover, and why that is scope rather than reach
 *
 * Cards, profiles and ports are the `Device` interface with `SPA_PARAM_Profile`
 * and `SPA_PARAM_Route`, reached by the same bind, the same subscription and the
 * same setter this already uses on a node. Virtual and combined sinks are
 * `create_object` on the core, whose offset the oracle has printed since the
 * first day. None of it is out of reach and saying otherwise would be citing a
 * decision as though it were a property of the graph.
 *
 * What is true is that none of it is built, and that one of them would not be a
 * translation of what the libpulse mixer does. A sink created through
 * `create_object` belongs to the connection that asked for it and goes when that
 * connection goes, where a server module outlives its client. For the obligation
 * `createVirtualSink` is written under, that is the better of the two rather
 * than a shortfall, and it is a difference worth deciding on rather than
 * inheriting.
 *
 * Each is absent from [capabilities] rather than present and answering false, so
 * a settings screen asks before it draws.
 */
internal class PipeWireMixer private constructor(
    private val applicationName: String,
    private val registry: PipeWireRegistry,
) : VolumeMixer {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    private val closed = AtomicBoolean(false)

    /** Which rows are this process's own, which is a comparison against the graph. */
    private val ourProcess = ProcessHandle.current().pid()

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_ENUMERATION,
        Capability.STREAM_CONTROL,
        Capability.STREAM_ROUTING,
        Capability.CAPTURE_ENUMERATION,
        Capability.CAPTURE_CONTROL,
        Capability.CAPTURE_ROUTING,
        Capability.DEVICE_VOLUME,
        Capability.STREAM_METERING,
        Capability.VIRTUAL_DEVICES,
    )

    override val isOpen: Boolean get() = !closed.get()

    /**
     * What each thing was before this process first changed it.
     *
     * Volume and mute are kept apart, and the reason is the libpulse mixer's:
     * one snapshot covering both would restore a field nobody here touched, so
     * lowering somebody's volume and putting it back would also undo a mute the
     * user set in between.
     */
    private val originalStreamVolumes = ConcurrentHashMap<Long, Float>()
    private val originalStreamMutes = ConcurrentHashMap<Long, Boolean>()
    private val originalDeviceVolumes = ConcurrentHashMap<String, Float>()
    private val originalDeviceMutes = ConcurrentHashMap<String, Boolean>()

    private val handlers = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /**
     * The rows as the last notification saw them, and the lock that keeps two
     * notifications from computing the difference against each other.
     *
     * The registry says only that something moved, which is all any backend
     * here says and all every consumer acts on. The contract asks for more than
     * that, so the difference is worked out here rather than asked for: a
     * snapshot, compared with the one before it.
     */
    private val snapshotLock = ReentrantLock()

    private var snapshot: Map<StreamId, AudioStream> = emptyMap()

    /** Registered once whatever the number of subscribers, and released with them. */
    private var unsubscribe: (() -> Unit)? = null

    /** Started at the first meter and released at [close]. */
    private val meterLock = ReentrantLock()

    private var meters: PipeWireLoop? = null

    override fun streams(): List<AudioStream> =
        if (closed.get()) emptyList() else registry.streams(ourProcess)

    /**
     * False also means not yet, and a consumer that has just seen the row
     * appear should try again.
     *
     * The graph describes an object in pieces: a node's global arrives first
     * and its parameters a moment later, and writing a volume means writing one
     * per channel, so until that parameter has landed there is no channel count
     * to write. Refused rather than guessed, because an array of the wrong
     * length sets some of a device's channels and leaves the rest.
     *
     * A row is settable within a moment of appearing and stays settable. What
     * this cannot do is tell that case apart from a stream that has gone, which
     * is the same false either way.
     */
    override fun setVolume(id: StreamId, volume: Float): Boolean {
        val serial = serialOf(id) ?: return false
        remember(id) { row -> originalStreamVolumes.putIfAbsent(serial, row.volume) }
        return registry.setStreamProps(serial, volume.coerceIn(0f, 1f), null)
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val serial = serialOf(id) ?: return false
        remember(id) { row -> originalStreamMutes.putIfAbsent(serial, row.muted) }
        return registry.setStreamProps(serial, null, muted)
    }

    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val serial = serialOf(id) ?: return false
        // Not recorded for restore, and deliberately. Where a stream plays is a
        // decision in the same sense the default device is, and a stream this
        // process moved has usually been moved because that is the feature.
        return registry.moveStream(serial, device.value)
    }

    override fun setDeviceVolume(device: DeviceId, volume: Float): Boolean {
        rememberDevice(device) { row -> row.volume?.let { originalDeviceVolumes.putIfAbsent(device.value, it) } }
        return registry.setDeviceProps(device.value, volume.coerceIn(0f, 1f), null)
    }

    override fun setDeviceMuted(device: DeviceId, muted: Boolean): Boolean {
        rememberDevice(device) { row -> row.muted?.let { originalDeviceMutes.putIfAbsent(device.value, it) } }
        return registry.setDeviceProps(device.value, null, muted)
    }

    /**
     * Not restored by [restoreAll], which the contract states and this follows:
     * a default somebody chose through a settings screen is a decision rather
     * than a change made on their behalf.
     *
     * Written for whichever lists the device is on, which is both for a device
     * that plays and records at once.
     */
    override fun setDefaultDevice(device: DeviceId): Boolean {
        if (closed.get()) return false
        var wrote = false
        StreamDirection.entries.forEach { direction ->
            val present = registry.devices(direction).any { it.id == device }
            if (present && registry.setDefaultDevice(device.value, direction)) wrote = true
        }
        return wrote
    }

    /**
     * Empty, and [Capability.DEVICE_PROFILES] is absent to say so.
     *
     * Not because the graph withholds them: a card is a `Device` global with
     * profile and route parameters on it, reachable by the machinery a node
     * already uses. It is not built.
     */
    override fun cards(): List<AudioCard> = emptyList()

    override fun setCardProfile(card: CardId, profile: String): Boolean = false

    override fun setDevicePort(device: DeviceId, port: String): Boolean = false

    /**
     * A device the machine does not have, made through the core's own factory.
     *
     * It belongs to this connection and goes when this connection goes, because
     * `object.linger` is left unset and the header describes that key as the
     * one making an object outlive its client. That is a different lifetime
     * from the server module the libpulse mixer loads, and it lines up with
     * what this call is documented under: a virtual sink left behind is a
     * device somebody finds in their settings and cannot account for.
     *
     * How that behaves when the connection ends without [close] running is not
     * something this suite establishes, since it cannot end one that way. What
     * it does establish is that the device goes when the mixer is closed and
     * when [restoreAll] runs without it having been removed.
     *
     * [channels] is honoured or the call is refused, never narrowed, and the
     * positions travel with it wherever the graph has a name for every one.
     */
    override fun createVirtualSink(name: String, channels: Int): DeviceId? {
        if (closed.get() || name.isBlank()) return null
        if (!registry.createNullSink(name, channels)) return null
        // Made and then waited for: the object exists when create_object
        // returns and appears on the graph a moment later, and a caller handed
        // an id it cannot yet use has been handed a promise rather than a
        // device.
        return awaitDevice(name)
    }

    override fun removeVirtualSink(id: DeviceId): Boolean = registry.removeNullSink(id.value)

    /**
     * Null, and it is the server refusing rather than this declining to ask.
     *
     * Playing one thing to two devices is a module the daemon loads, and a
     * graph that has not loaded it registers no factory for one, which a client
     * cannot change from outside. Where a machine has loaded it, this is where
     * the call would go.
     */
    override fun combineSinks(name: String, devices: List<DeviceId>): DeviceId? = null

    /**
     * Wait for a device just made to appear on the graph, or give up and undo
     * it.
     *
     * `create_object` answers with a proxy before the object has a global, so a
     * name that never shows up is a device the graph accepted and did not
     * build. Handing back an id for one would be handing back something every
     * later call answers false for.
     */
    private fun awaitDevice(name: String): DeviceId? {
        val deadline = System.nanoTime() + APPEAR_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (registry.devices(StreamDirection.PLAYBACK).any { it.id.value == name }) return DeviceId(name)
            Thread.sleep(APPEAR_POLL_MILLIS)
        }
        log.debug("the graph took {} and never showed it", name)
        registry.removeNullSink(name)
        return null
    }

    /**
     * Put back every volume and mute this process changed and has not changed
     * back.
     *
     * The contract's order runs from what makes devices exist towards what is
     * set on them. Nothing here makes a device exist, so what is left is the
     * last step of it, and each entry is taken out of the record before it is
     * applied: a restore that fails is not a restore to try again for the life
     * of the process.
     */
    override fun restoreAll() {
        val streamVolumes = drain(originalStreamVolumes)
        val streamMutes = drain(originalStreamMutes)
        val deviceVolumes = drain(originalDeviceVolumes)
        val deviceMutes = drain(originalDeviceMutes)
        // Devices this process made go first, which is the contract's own
        // order: a volume put back onto a sink that is about to vanish is
        // applied to nothing.
        val made = registry.createdSinkNames()
        made.forEach { runCatching { registry.removeNullSink(it) } }
        val total = made.size + streamVolumes.size + streamMutes.size + deviceVolumes.size + deviceMutes.size
        if (total == 0) return
        log.info("restoring {} setting(s) this process changed", total)
        streamVolumes.forEach { (serial, volume) ->
            runCatching { registry.setStreamProps(serial, volume, null) }
        }
        streamMutes.forEach { (serial, muted) ->
            runCatching { registry.setStreamProps(serial, null, muted) }
        }
        deviceVolumes.forEach { (name, volume) ->
            runCatching { registry.setDeviceProps(name, volume, null) }
        }
        deviceMutes.forEach { (name, muted) ->
            runCatching { registry.setDeviceProps(name, null, muted) }
        }
    }

    /**
     * The three events the contract asks for, worked out from the one the graph
     * gives.
     *
     * The registry reports that something moved and not what, which is what
     * every backend here reports and what every consumer acts on by re-reading.
     * A mixer row is the exception: a list redrawn wholesale loses the scroll
     * position and the drag in progress, so the difference is computed here.
     */
    override fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit {
        if (closed.get()) return {}
        handlers.add(handler)
        snapshotLock.withLock {
            if (unsubscribe == null) {
                snapshot = streams().associateBy { it.id }
                unsubscribe = registry.onChanged(::publish)
            }
        }
        return { handlers.remove(handler) }
    }

    /**
     * Watch one row's level, by recording it.
     *
     * The contract says this costs something and here it is plain what: a
     * capture stream aimed at that node, which is the same mechanism recording
     * one application uses, and a thread reading it. So it is a subscription
     * with a cancel rather than a property, and a mixer drawing twenty rows
     * should watch the ones on screen.
     *
     * On a loop of its own rather than the one this connection runs on. A
     * meter's callback on the registry's loop would hold up the registry's own
     * dispatch, so a mixer with a meter open would stop hearing about the
     * streams it is metering.
     *
     * Playback rows only, and [Capability.CAPTURE_METERING] is absent to say so.
     * Aiming at a row that is itself recording would tap what it is recording
     * from, which is the device rather than that row: a meter that moved because
     * somebody else was talking is worse than no meter, which is the same
     * conclusion the libpulse mixer reached by a different route.
     *
     * Each call opens its own stream. Two meters on one row is two recordings,
     * which is wasteful and correct, and the alternative is sharing state
     * between subscriptions that cancel independently.
     */
    override fun meter(id: StreamId, handler: (Float) -> Unit): () -> Unit {
        if (closed.get()) return {}
        val playing = streams().firstOrNull { it.id == id && it.direction == StreamDirection.PLAYBACK }
        if (playing == null) return { }
        val loop = meterLoop() ?: return {}
        val source = PipeWireSource(
            loop,
            SourceConfig(
                applicationName = "$applicationName meter",
                captureStream = id,
                // Short, because a meter wants what is happening rather than
                // what happened: a long buffer is a level that lags the sound
                // by its own depth.
                latency = LatencyProfile.LOW,
            ),
            METER_CAPABILITIES,
            registry,
        )
        val running = AtomicBoolean(true)
        val thread = Thread({ pump(source, running, handler) }, "libsound-pipewire-meter")
        thread.isDaemon = true
        // Named rather than written as the last expression of each branch: a
        // lambda literal after a call is read as that call's trailing argument.
        val cancel: () -> Unit = {
            running.set(false)
            runCatching { source.close() }
        }
        val none: () -> Unit = {}
        return runCatching {
            source.open(AudioFormat(METER_RATE, METER_CHANNELS, PcmEncoding.F32LE))
            thread.start()
            cancel
        }.getOrElse { failure ->
            log.debug("no meter on {}: {}", id, failure.message)
            runCatching { source.close() }
            none
        }
    }

    /**
     * Read windows and hand the loudest sample of each one over.
     *
     * The loudest rather than an average, because a meter is drawn to show that
     * something is happening and an average of a window that is mostly silence
     * shows that nothing is.
     */
    private fun pump(source: PipeWireSource, running: AtomicBoolean, handler: (Float) -> Unit) {
        val window = ByteArray(METER_RATE / METER_WINDOWS_PER_SECOND * METER_CHANNELS * Float.SIZE_BYTES)
        runCatching {
            while (running.get()) {
                source.read(window, 0, window.size)
                if (!running.get()) return
                var peak = 0f
                var at = 0
                while (at + Float.SIZE_BYTES <= window.size) {
                    val bits = (window[at].toInt() and 0xFF) or
                        ((window[at + 1].toInt() and 0xFF) shl 8) or
                        ((window[at + 2].toInt() and 0xFF) shl 16) or
                        ((window[at + 3].toInt() and 0xFF) shl 24)
                    val sample = abs(Float.fromBits(bits))
                    if (sample > peak) peak = sample
                    at += Float.SIZE_BYTES
                }
                runCatching { handler(peak.coerceIn(0f, 1f)) }
                    .onFailure { log.warn("meter handler threw: {}", it.message) }
            }
        }.onFailure { if (running.get()) log.debug("meter stopped: {}", it.message) }
    }

    /**
     * The loop every meter runs on, started at the first one and kept.
     *
     * Kept rather than released with the last meter, because a loop is one idle
     * thread and closing it on the last cancel would race the next subscription.
     */
    private fun meterLoop(): PipeWireLoop? = meterLock.withLock {
        if (closed.get()) return null
        meters ?: PipeWireLoop.startOrNull("$applicationName meters")?.also { meters = it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handlers.clear()
        snapshotLock.withLock {
            unsubscribe?.invoke()
            unsubscribe = null
        }
        runCatching { restoreAll() }.onFailure { log.warn("restore on close threw: {}", it.message) }
        // The meters' loop before the registry's, because a meter's stream was
        // created against the registry and checked through it.
        meterLock.withLock {
            meters?.let { runCatching { it.close() } }
            meters = null
        }
        runCatching { registry.close() }
    }

    // -- internals ---------------------------------------------------------------

    /**
     * The serial a [StreamId] carries, or null for one this cannot act on.
     *
     * The same shape the libpulse mixer hands out, parsed by the same code, so a
     * consumer holding one id can take it to either mixer and to a capture
     * stream. Its number is the object serial, which is also the index
     * `pipewire-pulse` gives the same object.
     */
    private fun serialOf(id: StreamId): Long? {
        if (closed.get()) return null
        return PulseStreamHandle.parse(id)?.index?.toLong()
    }

    /** Record what a row was, once, before this process first changes it. */
    private inline fun remember(id: StreamId, record: (AudioStream) -> Unit) {
        streams().firstOrNull { it.id == id }?.let(record)
    }

    private inline fun rememberDevice(device: DeviceId, record: (dev.hivens.libsound.AudioDevice) -> Unit) {
        StreamDirection.entries.asSequence()
            .flatMap { registry.devices(it).asSequence() }
            .firstOrNull { it.id == device }
            ?.let(record)
    }

    private fun <K, V> drain(from: ConcurrentHashMap<K, V>): List<Pair<K, V>> {
        val taken = from.entries.map { it.key to it.value }
        taken.forEach { from.remove(it.first) }
        return taken
    }

    /**
     * Compare the graph against what it was and tell everybody what moved.
     *
     * Runs on the registry's own dispatch thread, which is single, so the
     * comparison is serialised by construction; the lock is there because a
     * subscription arriving at the same moment reads the same field.
     */
    private fun publish() {
        if (closed.get()) return
        val events = snapshotLock.withLock {
            val fresh = streams().associateBy { it.id }
            val previous = snapshot
            snapshot = fresh
            buildList {
                fresh.forEach { (id, row) ->
                    val before = previous[id]
                    when {
                        before == null -> add(StreamEvent.Appeared(row))
                        before != row -> add(StreamEvent.Changed(row))
                        else -> Unit
                    }
                }
                previous.keys.forEach { id -> if (id !in fresh) add(StreamEvent.Gone(id)) }
            }
        }
        if (events.isEmpty()) return
        val listeners = handlers.toList()
        events.forEach { event ->
            listeners.forEach { handler ->
                runCatching { handler(event) }.onFailure { log.warn("stream listener threw: {}", it.message) }
            }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /** What a metering stream is allowed to say about itself. */
        private val METER_CAPABILITIES = Capabilities.of(Capability.CAPTURE)

        /** The shape a meter reads in, which the graph converts to whatever the row is. */
        private const val METER_RATE = 48_000
        private const val METER_CHANNELS = 2

        /** Fast enough to look live, slow enough to cost nothing worth measuring. */
        private const val METER_WINDOWS_PER_SECOND = 20

        /** How long a device just made has to appear before it is taken back. */
        private const val APPEAR_TIMEOUT_NANOS = 5_000_000_000L
        private const val APPEAR_POLL_MILLIS = 25L

        /**
         * Open a connection of this mixer's own, or null where no graph
         * answered.
         *
         * Null is the ordinary answer on a machine running real PulseAudio and
         * on one with no sound server, both of which the selection above falls
         * back from.
         */
        fun openOrNull(applicationName: String): VolumeMixer? {
            val registry = PipeWireRegistry.openOrNull("$applicationName mixer") ?: return null
            return runCatching { PipeWireMixer(applicationName, registry) }.getOrElse {
                log.debug("no PipeWire mixer: {}", it.message)
                runCatching { registry.close() }
                null
            }
        }
    }
}
