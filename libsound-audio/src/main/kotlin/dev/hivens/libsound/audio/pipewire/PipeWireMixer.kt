package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioCard
import dev.hivens.libsound.AudioStream
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

    override fun streams(): List<AudioStream> =
        if (closed.get()) emptyList() else registry.streams(ourProcess)

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
     * Null, and [Capability.VIRTUAL_DEVICES] is absent.
     *
     * `create_object` on the core is what would make one, and the object it
     * makes belongs to this connection rather than to the server. That is a
     * different lifetime from the module the libpulse mixer loads, and the one
     * the restore obligation would prefer, so it is a decision to take rather
     * than a call to translate.
     */
    override fun createVirtualSink(name: String, channels: Int): DeviceId? = null

    override fun removeVirtualSink(id: DeviceId): Boolean = false

    override fun combineSinks(name: String, devices: List<DeviceId>): DeviceId? = null

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
        val total = streamVolumes.size + streamMutes.size + deviceVolumes.size + deviceMutes.size
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
     * Nothing, and [Capability.STREAM_METERING] is absent so a consumer asks
     * rather than watching a meter that never moves.
     *
     * Reaching a stream's level means attaching to the audio itself, which here
     * is a capture stream aimed at that node and a loop of its own to run it on:
     * putting it on the loop this connection uses would have a meter's callback
     * hold up the registry's own dispatch. That is a mechanism rather than a
     * line, so it is absent and said to be absent.
     */
    override fun meter(id: StreamId, handler: (Float) -> Unit): () -> Unit = {}

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handlers.clear()
        snapshotLock.withLock {
            unsubscribe?.invoke()
            unsubscribe = null
        }
        runCatching { restoreAll() }.onFailure { log.warn("restore on close threw: {}", it.message) }
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
            return runCatching { PipeWireMixer(registry) }.getOrElse {
                log.debug("no PipeWire mixer: {}", it.message)
                runCatching { registry.close() }
                null
            }
        }
    }
}
