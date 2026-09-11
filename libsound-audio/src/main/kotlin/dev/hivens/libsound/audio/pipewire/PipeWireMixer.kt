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

    /**
     * Decided once at open and constant afterwards, which is what the interface
     * promises a settings screen it may rely on.
     *
     * Routing is the one entry that is not a property of this code. Moving a
     * stream is a line written into the session manager's metadata object and
     * acted on by that manager, so a graph running without one has nothing to
     * write into and nothing that would read it. Claimed only where that object
     * is bound, because the interface says plainly that a device menu on a
     * mixer row is a control a consumer should not draw where it cannot work.
     *
     * Answerable by now rather than guessed at: the second round trip the
     * registry makes at open is there so that what its binds brought back has
     * arrived before anybody asks.
     */
    override val capabilities: Capabilities = Capabilities(
        buildSet {
            add(Capability.STREAM_ENUMERATION)
            add(Capability.STREAM_CONTROL)
            add(Capability.CAPTURE_ENUMERATION)
            add(Capability.CAPTURE_CONTROL)
            add(Capability.DEVICE_VOLUME)
            add(Capability.STREAM_METERING)
            add(Capability.VIRTUAL_DEVICES)
            if (registry.hasMetadata()) {
                add(Capability.STREAM_ROUTING)
                add(Capability.CAPTURE_ROUTING)
            }
        },
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

    private var meterLoop: PipeWireLoop? = null

    /**
     * Live meters, closed with the mixer so that none outlives the loop its
     * stream was created on.
     *
     * The libpulse mixer keeps the same list for the same reason. Without it
     * the only reference to a running meter is the cancel handed to whoever
     * asked for it, and a consumer that closes the mixer without cancelling
     * first leaves a capture stream on the graph, a thread parked reading it,
     * and a recording indicator lit on the desktop until the process ends.
     */
    private val meters = CopyOnWriteArrayList<Meter>()

    /** One live meter: the stream it reads and the thread reading it. */
    private class Meter(private val source: PipeWireSource, private val running: AtomicBoolean) {
        fun close() {
            // The flag first, so the reader stops rather than reporting the
            // closed stream as a meter that failed.
            running.set(false)
            runCatching { source.close() }
        }
    }

    override fun streams(): List<AudioStream> =
        if (closed.get()) emptyList() else registry.streams(ourProcess)

    /**
     * False also means not yet, and a consumer that has just seen the row
     * appear should try again.
     *
     * This binding learns a node's volume by subscribing to it when the node's
     * global arrives, so there is a moment after a row appears in which the
     * graph has not answered yet. Two things are missing during it: the channel
     * count, without which a volume cannot be written at all, and the value to
     * put back at close.
     *
     * Both are refused rather than guessed. An array of the wrong length sets
     * some of a device's channels and leaves the rest, and a remembered value
     * nobody measured is worse than either, because it is applied later to
     * somebody else's audio by a restore that believes it is undoing something.
     *
     * A row is settable within a moment of appearing and stays settable. What
     * this cannot do is tell that case apart from a stream that has gone, which
     * is the same false either way.
     */
    override fun setVolume(id: StreamId, volume: Float): Boolean {
        val serial = serialOf(id) ?: return false
        val before = registry.streamSettings(serial)?.volume ?: return false
        if (!registry.setStreamProps(serial, volume.coerceIn(0f, 1f), null)) return false
        // After the write and only on success: a record of a change that did
        // not happen is a restore that moves something this process never
        // touched.
        originalStreamVolumes.putIfAbsent(serial, before)
        return true
    }

    /** As [setVolume], refused for the same reasons and recorded the same way. */
    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val serial = serialOf(id) ?: return false
        val before = registry.streamSettings(serial)?.muted ?: return false
        if (!registry.setStreamProps(serial, null, muted)) return false
        originalStreamMutes.putIfAbsent(serial, before)
        return true
    }

    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val serial = serialOf(id) ?: return false
        // Not recorded for restore, and deliberately. Where a stream plays is a
        // decision in the same sense the default device is, and a stream this
        // process moved has usually been moved because that is the feature.
        return registry.moveStream(serial, device.value)
    }

    /** As [setVolume], on the speaker everything plays through rather than one row. */
    override fun setDeviceVolume(device: DeviceId, volume: Float): Boolean {
        val before = registry.deviceSettings(device.value)?.volume ?: return false
        if (!registry.setDeviceProps(device.value, volume.coerceIn(0f, 1f), null)) return false
        originalDeviceVolumes.putIfAbsent(device.value, before)
        return true
    }

    /** As [setDeviceVolume], and answering for the same reasons. */
    override fun setDeviceMuted(device: DeviceId, muted: Boolean): Boolean {
        val before = registry.deviceSettings(device.value)?.muted ?: return false
        if (!registry.setDeviceProps(device.value, null, muted)) return false
        originalDeviceMutes.putIfAbsent(device.value, before)
        return true
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
        return awaitDevice(name, channels)
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
    private fun awaitDevice(name: String, channels: Int): DeviceId? {
        val deadline = System.nanoTime() + APPEAR_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val laid = registry.deviceChannels(name)
            if (laid != null) {
                if (laid == channels) return DeviceId(name)
                // Honoured or refused, never narrowed. A caller that asked for
                // a six channel bus and was handed a stereo one finds out by
                // hearing four of its channels vanish.
                log.info("the graph laid {} out with {} channel(s) rather than {}", name, laid, channels)
                break
            }
            try {
                Thread.sleep(APPEAR_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                // The flag back, because swallowing it leaves a caller that was
                // being shut down with no sign of it. The device goes either
                // way: it belongs to this connection, and nothing else holds a
                // name for it.
                Thread.currentThread().interrupt()
                log.debug("waiting for {} was interrupted: {}", name, interrupted.message)
                break
            }
        }
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
        val first = snapshotLock.withLock {
            if (unsubscribe != null) {
                false
            } else {
                snapshot = streams().associateBy { it.id }
                unsubscribe = registry.onChanged(::publish)
                true
            }
        }
        // A row that appeared between the snapshot and the subscription is on
        // neither of them: the event announcing it went out before this was
        // listening, and the snapshot was taken before it existed. One
        // comparison closes that window, and costs a list read where nothing
        // moved.
        if (first) publish()
        return { release(handler) }
    }

    /**
     * Drop one subscriber, and the registry's own subscription with the last of
     * them.
     *
     * The snapshot goes with it. One kept across a gap with nobody listening
     * would be compared against a graph that had moved on, so whoever
     * subscribed next would be told about every change made while nobody was.
     */
    private fun release(handler: (StreamEvent) -> Unit) {
        handlers.remove(handler)
        snapshotLock.withLock {
            if (handlers.isEmpty()) {
                unsubscribe?.invoke()
                unsubscribe = null
                snapshot = emptyMap()
            }
        }
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
        // Named rather than written as the last expression of each branch: a
        // lambda literal after a call is read as that call's trailing argument.
        val none: () -> Unit = {}
        // The lock is held across the open rather than only across starting the
        // loop. The stream is created on that loop and [close] destroys it, so
        // released in between, a meter opening while the mixer closes builds a
        // stream on a loop being torn down underneath it.
        return meterLock.withLock {
            val loop = meterLoopOrStart() ?: return@withLock none
            val source = PipeWireSource(
                loop,
                SourceConfig(
                    applicationName = "$applicationName meter",
                    captureStream = id,
                    // Short, because a meter wants what is happening rather
                    // than what happened: a long buffer is a level that lags
                    // the sound by its own depth.
                    latency = LatencyProfile.LOW,
                ),
                METER_CAPABILITIES,
                registry,
            )
            val running = AtomicBoolean(true)
            val meter = Meter(source, running)
            val thread = Thread({ pump(source, running, handler) }, "libsound-pipewire-meter")
            thread.isDaemon = true
            val cancel: () -> Unit = { if (meters.remove(meter)) meter.close() }
            runCatching {
                source.open(AudioFormat(METER_RATE, METER_CHANNELS, PcmEncoding.F32LE))
                meters.add(meter)
                thread.start()
                cancel
            }.getOrElse { failure ->
                log.debug("no meter on {}: {}", id, failure.message)
                runCatching { source.close() }
                none
            }
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
    private fun meterLoopOrStart(): PipeWireLoop? = meterLock.withLock {
        if (closed.get()) return null
        meterLoop ?: PipeWireLoop.startOrNull("$applicationName meters")?.also { meterLoop = it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handlers.clear()
        snapshotLock.withLock {
            unsubscribe?.invoke()
            unsubscribe = null
        }
        runCatching { restoreAll() }.onFailure { log.warn("restore on close threw: {}", it.message) }
        // Every meter before the loop they run on, and that loop before the
        // registry's: a meter's stream was created against the registry and
        // checked through it, and destroying the loop while a stream on it is
        // still open leaves that stream on the graph for good.
        meterLock.withLock {
            meters.forEach { runCatching { it.close() } }
            meters.clear()
            meterLoop?.let { runCatching { it.close() } }
            meterLoop = null
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
        if (closed.get() || handlers.isEmpty()) return
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
