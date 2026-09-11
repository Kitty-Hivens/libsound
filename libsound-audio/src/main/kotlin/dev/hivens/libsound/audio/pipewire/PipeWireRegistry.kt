package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.audio.pulse.PulseStreamHandle
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What is on the graph, watched rather than asked for.
 *
 * The registry sends one event per object at connect and one more whenever the
 * set changes, each carrying an id, a type and the object's whole property
 * dict. So a device list is that stream filtered by `media.class`, and it needs
 * no round trip at all: the answer arrives before anybody asks, which is the
 * opposite shape from the libpulse side, where every enumeration is a call and
 * a wait.
 *
 * ## Its own connection
 *
 * Not the streams'. The libpulse mixer takes a second connection for the same
 * reason and states it in the same words: a subscription that reports every
 * object on the machine puts introspection traffic on the connection carrying
 * audio timing, and the two have no business sharing a socket.
 *
 * ## What it binds, and why those
 *
 * Most of a device row travels on the global's own dict, so a list needs no
 * proxy. Two things do not, and each of them is a bind.
 *
 * Which device is default is not a property of the graph at all: the session
 * manager writes it into a metadata object, so it means binding that object,
 * chosen out of the several the graph carries by name.
 *
 * A device's own volume is a parameter of its node, and a parameter is only
 * reachable through a bind. So each audio node gets one and a subscription to
 * the one parameter that carries the volume and the mute, which is why a slider
 * somebody else moved arrives here as an event rather than at the next re-read.
 *
 * Nothing else is bound. What else is on the graph it merely hears: the running
 * applications go into a set of their own, keyed by the serial that names one
 * for good, and that set answers one question, which is whether a stream
 * somebody asked to record is still there.
 *
 * What is playing, how loud and where remains `VolumeMixer`'s question, and
 * this is not a second one.
 */
internal class PipeWireRegistry private constructor(
    private val loop: PipeWireLoop,
    private val context: MemorySegment,
    private val core: MemorySegment,
    private val registry: MemorySegment,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    private val lib = loop.lib

    private val closed = AtomicBoolean(false)

    /**
     * One request into the graph at a time, and none at all once closed.
     *
     * Two jobs in one lock, and they are the same job seen from two sides.
     *
     * A round trip parks on the loop's condition, which releases the loop lock
     * while it waits, so two threads syncing at once would each wait on the
     * other's sequence number and one of them would be told the graph had
     * answered when it had not. That is the libpulse mixer's round-trip lock,
     * needed here for the same reason.
     *
     * And [closed] on its own is a check rather than a barrier: a caller past
     * it is about to enter a loop this may be halfway through destroying.
     * [close] holds this across the whole teardown, so a caller that got in
     * first finishes first and one arriving later finds the flag already set.
     */
    private val calls = ReentrantLock()

    /**
     * Every audio node the graph has told us about, by its global id.
     *
     * Written on the loop thread by the events and read by whoever asks, which
     * is what makes it concurrent rather than guarded: a device list is a
     * snapshot and a consumer that wants to know about a change subscribes.
     */
    private val nodes = ConcurrentHashMap<Int, GraphNode>()

    /**
     * One audio node, whether it is something to play to or somebody playing.
     *
     * Both kinds in one map rather than two, because everything after the
     * `media.class` is identical: each is bound, each is subscribed to the same
     * parameter, each reports its volume and its state through the same two
     * callbacks. Splitting them would be that machinery written twice.
     *
     * [directions] is a set because the graph has devices that are both:
     * `Audio/Duplex` plays and records, and appears in each list with that
     * list's direction stamped on it. For a stream it holds the one direction
     * it flows in, and [isStream] is what tells the two kinds apart.
     */
    private data class GraphNode(
        /** `node.name`, the stable identity a target names. */
        val name: String,
        /** What a person reads, which falls back to the name. */
        val label: String,
        val directions: Set<StreamDirection>,
        val isStream: Boolean,
        /**
         * The serial that names this object for the life of the graph, which is
         * what a [dev.hivens.libsound.StreamId] carries.
         */
        val serial: Long,
        /**
         * The global's own property dict.
         *
         * Kept whole for streams, where a mixer row is most of it: the
         * application's name, its icon, what it is playing and what it says the
         * audio is for. A device row needs three of them and they are read out
         * above, so this is here for the rows that need the rest.
         */
        val properties: Map<String, String>,
        val volume: Float? = null,
        val muted: Boolean? = null,
        /**
         * How many channels the node has, off its own channel map, or zero
         * until that map has arrived.
         *
         * Kept because writing a volume back means writing one per channel, and
         * a two entry array sent to a six channel node sets two of its channels
         * and leaves four where they were.
         */
        val volumeChannels: Int = 0,
        val suspended: Boolean = false,
        /** True while the node is actually rendering rather than merely attached. */
        val running: Boolean = false,
    ) {
        /**
         * The device row this node makes, in one direction.
         *
         * isMonitor stays false and that is not a default standing in for the
         * unknown. A sink's monitor is ports on the sink's own node here rather
         * than a node of its own, so there is nothing in this list that is one:
         * what `pipewire-pulse` presents as `<sink>.monitor` it synthesises, and
         * the graph carries no such object. Measured on a graph whose only node
         * was a null sink, where the pulse protocol listed a monitor source and
         * the node list had none.
         */
        fun asDevice(direction: StreamDirection, isDefault: Boolean): AudioDevice = AudioDevice(
            id = DeviceId(name),
            name = label,
            isDefault = isDefault,
            direction = direction,
            volume = volume,
            muted = muted,
            isSuspended = suspended,
        )
    }

    /**
     * Which link joins which two nodes, by the link's own global id.
     *
     * The only thing on the graph that says where a stream's audio goes. A
     * stream names a target only when it asked for one and most do not, so a
     * mixer row's device is found by following the link out of that stream's
     * node rather than by reading any property of the stream.
     *
     * A pair of node ids rather than the ports they joined: several links carry
     * one stream, one per channel, and for this question they all answer the
     * same.
     */
    private val links = ConcurrentHashMap<Int, Pair<Int, Int>>()

    /**
     * What the session manager currently calls the default, by `node.name`.
     *
     * Kept apart from [nodes] rather than stamped onto the entries, because the
     * two change independently: a default moving is one metadata event and no
     * node event at all, and rewriting every row on each move would be work to
     * keep correct for nothing.
     */
    @Volatile
    private var defaultSinkName: String? = null

    @Volatile
    private var defaultSourceName: String? = null

    /** The metadata global this is bound to, or -1 while nothing is. */
    @Volatile
    private var metadataId = NO_GLOBAL

    @Volatile
    private var metadata: MemorySegment = MemorySegment.NULL

    /**
     * Every application currently playing, by the serial that names it for good.
     *
     * Not a device list and never offered as one. It answers exactly one
     * question, asked before a capture stream is aimed at one of them: is the
     * thing a caller wants to record still on the graph. Getting that wrong
     * would record a different application, or a microphone, which is the one
     * mistake this feature must not make.
     */
    private val playing = ConcurrentHashMap<Long, Int>()

    /**
     * A proxy and a listener hook for every audio node, and the hooks going
     * spare after one was removed.
     *
     * Touched only on the loop's own thread and, in [close], under the loop
     * lock, which is the same thing serialised: an ordinary map is enough where
     * [nodes] needs a concurrent one, because nothing outside reads these.
     */
    private val nodeProxies = HashMap<Int, MemorySegment>()

    private val nodeHooks = HashMap<Int, MemorySegment>()

    private val spareHooks = ArrayDeque<MemorySegment>()

    /**
     * Devices this connection made, by the name they were made under.
     *
     * The proxy is the ownership: destroying it destroys the device, and losing
     * it without destroying it would leave one on the graph until the
     * connection went. Touched only under the loop lock, like the maps above.
     */
    private val createdSinks = HashMap<String, MemorySegment>()

    /** The sequence number the barrier is waiting for, and whether it arrived. */
    @Volatile
    private var pendingSeq = NO_SEQ

    @Volatile
    private var syncDone = false

    /** Set when the round trip ended because the graph refused it, not because it answered. */
    @Volatile
    private var syncFailed = false

    /**
     * The proxy a write is waiting on, and whether the graph refused that
     * write.
     *
     * Every method on a proxy is a one-way message. `set_param` returns as soon
     * as its bytes are written, so what it reports is that the request went
     * out, and a refusal arrives afterwards on the core's own error event,
     * naming the proxy it was about. Matching the two up is what lets a setter
     * answer for the graph rather than for the socket.
     *
     * One at a time is enough because [calls] lets one write into the graph at
     * a time. [NO_PROXY] is safe as the resting value: the ids in an error are
     * the ones this connection was handed, and they start at zero.
     */
    @Volatile
    private var watchedProxy = NO_PROXY

    @Volatile
    private var proxyRefused = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Handlers never run on the loop thread.
     *
     * The rule every backend here needed: the natural response to a device
     * event is to re-read the device list, and doing that on the thread that
     * would deliver the answer is a loop waiting for itself.
     */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-pipewire-events").apply { isDaemon = true }
    }

    /** Holds the events structs, their stubs, and the hooks they are registered through. */
    private val stubArena: Arena = Arena.ofShared()

    /**
     * The devices of one direction, the default first where one is known.
     *
     * The order is the contract's and it is not cosmetic: a settings screen
     * that draws the list in the order it was handed puts the device audio is
     * actually going to wherever the sort happened to leave it.
     */
    fun devices(direction: StreamDirection): List<AudioDevice> {
        val default = defaultName(direction)
        return nodes.values.asSequence()
            .filter { !it.isStream && direction in it.directions }
            .map { it.asDevice(direction, it.name == default) }
            .sortedWith(compareByDescending<AudioDevice> { it.isDefault }.thenBy { it.name })
            .toList()
    }

    /**
     * The device the session manager calls the default, or null.
     *
     * Null covers three cases a consumer treats alike and this cannot tell
     * apart: no metadata object on the graph at all, which is a graph with no
     * session manager; a default that has been cleared; and a default naming a
     * node this list has not got, which is what a monitor is on a graph with no
     * real input. The contract already defines null as unknown, and the three
     * are honestly unknown rather than distinguishable.
     */
    fun defaultDevice(direction: StreamDirection): AudioDevice? {
        val default = defaultName(direction) ?: return null
        return nodes.values
            .firstOrNull { !it.isStream && direction in it.directions && it.name == default }
            ?.asDevice(direction, isDefault = true)
    }

    /**
     * Whether the graph still carries the application a capture was aimed at.
     *
     * The serial rather than the global id, because a global id is recycled and
     * a serial is not: an id that has come round again would name a different
     * application and a caller would be handed its audio instead.
     */
    fun isPlaying(serial: Long): Boolean = playing.containsKey(serial)

    /**
     * Whether the object a move is written into is bound.
     *
     * False on a graph running without a session manager, where there is
     * nothing to write a target into and nothing that would act on one. What a
     * mixer builds its routing capability out of, so that a device menu is not
     * drawn where it cannot work.
     */
    fun hasMetadata(): Boolean = metadata.address() != 0L

    /**
     * How many channels a device has, off its own channel map, or null while
     * the graph has not said.
     *
     * What a device asked for with a channel count is checked against. The map
     * comes back on the node's own parameters rather than on the global, so it
     * arrives a moment after the device does.
     */
    fun deviceChannels(name: String): Int? = nodes.values
        .firstOrNull { !it.isStream && it.name == name }
        ?.volumeChannels?.takeIf { it > 0 }

    /**
     * Everybody using the graph, in both directions, as a mixer draws them.
     *
     * Most of a row is the global's own property dict and needs nothing asked
     * for. The volume and the mute come from the parameter every audio node is
     * subscribed to, and the device from following the node's links, which is
     * the only thing on the graph that says where a stream's audio goes.
     *
     * A row whose serial the graph never gave is left out. The serial is the
     * whole of the identity a caller gets back, and one that cannot be named
     * cannot be acted on, so offering it would be offering a row whose slider
     * does nothing.
     */
    fun streams(ourProcess: Long): List<AudioStream> = nodes.entries.asSequence()
        .filter { it.value.isStream && it.value.serial != NO_SERIAL }
        .mapNotNull { (id, node) -> row(id, node, ourProcess) }
        .sortedBy { it.applicationName ?: it.id.value }
        .toList()

    private fun row(id: Int, node: GraphNode, ourProcess: Long): AudioStream? {
        val direction = node.directions.firstOrNull() ?: return null
        // The same shape the libpulse mixer hands out, because a consumer holds
        // one id and may take it to either. Its number is the object serial,
        // which is also the index `pipewire-pulse` gives the same object.
        if (node.serial > Int.MAX_VALUE) return null
        val handle = PulseStreamHandle(direction, node.serial.toInt())
        val properties = node.properties
        return AudioStream(
            id = handle.id(),
            applicationName = properties[SpaAbi.KEY_APP_NAME] ?: node.label,
            applicationId = properties[SpaAbi.KEY_APP_ID],
            iconName = properties[SpaAbi.KEY_APP_ICON_NAME],
            mediaName = properties[SpaAbi.KEY_MEDIA_NAME],
            mediaRole = roleOf(properties[SpaAbi.KEY_MEDIA_ROLE]),
            device = deviceOf(id, direction),
            volume = node.volume ?: 1f,
            muted = node.muted ?: false,
            active = node.running,
            isOurs = properties[SpaAbi.KEY_APP_PROCESS_ID]?.toLongOrNull() == ourProcess,
            direction = direction,
        )
    }

    /**
     * What the node calls its role, back into the enum a consumer knows.
     *
     * Case-insensitively, because the two sides disagree about it: the wire
     * names are lower case and this library's own streams write them capitalised,
     * which is what the graph's own tools show. A role this has no name for is
     * null rather than a guess.
     */
    private fun roleOf(role: String?): MediaRole? {
        if (role == null) return null
        return MediaRole.entries.firstOrNull { it.wireName.equals(role, ignoreCase = true) }
    }

    fun onChanged(handler: () -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        // Drained rather than killed: a handler is re-reading the list, which
        // touches nothing native, but it may still be running when the arena
        // holding the stubs goes.
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        calls.withLock {
            runCatching {
                loop.locked {
                    // Devices this connection made go first: each is a proxy
                    // like any other, and one destroyed after the connection
                    // would be destroyed by the connection going, which works
                    // and says nothing about whether this remembered to.
                    createdSinks.values.forEach { proxy ->
                        runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
                    }
                    createdSinks.clear()
                    // Every bound proxy before the registry that handed them
                    // out, and all of it before the connection they went on.
                    nodeProxies.keys.toList().forEach { releaseNode(it) }
                    releaseMetadata()
                    lib.handle("pw_proxy_destroy").invokeExact(registry) as Unit
                    lib.handle("pw_core_disconnect").invokeExact(core) as Int
                    lib.handle("pw_context_destroy").invokeExact(context) as Unit
                }
            }.onFailure { log.warn("registry teardown threw: {}", it.message) }
            // The loop is stopped inside this, and only then is the arena
            // released: no upcall can be in flight past a stopped loop.
            loop.close()
            runCatching { stubArena.close() }
        }
    }

    // -- the events, on the loop's own thread ---------------------------------

    // Public rather than internal for the reason every other upcall here is:
    // Kotlin mangles an internal name and findVirtual looks up what is written.

    /**
     * One object on the graph. Audio nodes are kept, the metadata object
     * holding the defaults is bound, and everything else is passed over.
     */
    fun onGlobal(
        unusedData: MemorySegment,
        id: Int,
        unusedPermissions: Int,
        type: MemorySegment,
        unusedVersion: Int,
        props: MemorySegment,
    ) {
        runCatching {
            if (props.address() == 0L) return@runCatching
            when (type.readCString()) {
                SpaAbi.INTERFACE_NODE -> addNode(id, readDict(props))
                SpaAbi.INTERFACE_METADATA -> bindDefaults(id, readDict(props))
                SpaAbi.INTERFACE_LINK -> addLink(id, readDict(props))
                else -> return@runCatching
            }
        }.onFailure { log.debug("registry global threw: {}", it.message) }
    }

    fun onGlobalRemove(unusedData: MemorySegment, id: Int) {
        runCatching {
            if (id == metadataId) {
                // The proxy dies with its global. Dropping the defaults with it
                // is the honest answer rather than keeping the last name seen,
                // which would name a device on a graph that no longer has a
                // session manager to have chosen one.
                releaseMetadata()
                defaultSinkName = null
                defaultSourceName = null
                fire()
            }
            releaseNode(id)
            playing.entries.removeIf { it.value == id }
            // A link going is a stream that stopped playing to something, which
            // is a row whose device changed rather than a row that left.
            if (links.remove(id) != null) fire()
            if (nodes.remove(id) != null) fire()
        }.onFailure { log.debug("registry global_remove threw: {}", it.message) }
    }

    /**
     * One entry of the metadata object, which is how a default arrives and how
     * a move is reported.
     *
     * Returns an int because the event does: nothing reads it, and zero is what
     * the library's own implementations answer.
     */
    fun onMetadataProperty(
        unusedData: MemorySegment,
        unusedSubject: Int,
        key: MemorySegment,
        unusedType: MemorySegment,
        value: MemorySegment,
    ): Int {
        runCatching {
            // A null value is the entry being cleared, which is a default going
            // away rather than an event to ignore.
            val name = value.readCString()?.let(::nameIn)
            when (key.readCString()) {
                SpaAbi.METADATA_KEY_DEFAULT_SINK -> defaultSinkName = name
                SpaAbi.METADATA_KEY_DEFAULT_SOURCE -> defaultSourceName = name
                else -> return 0
            }
            fire()
        }.onFailure { log.debug("metadata property threw: {}", it.message) }
        return 0
    }

    /**
     * One parameter of one node, which is where a device's own volume is.
     *
     * `channelVolumes` rather than `volume`, and the loudest of them, because
     * that is what a slider shows and what the libpulse side reports through
     * `pa_cvolume_max`. The two scales agree without conversion: both are
     * linear amplitude, which was measured rather than assumed, by setting a
     * sink to half through the pulse protocol and reading 0.125 back here.
     *
     * [data] is the node's global id, handed over at bind and never
     * dereferenced.
     */
    fun onNodeParam(
        data: MemorySegment,
        unusedSeq: Int,
        id: Int,
        unusedIndex: Int,
        unusedNext: Int,
        param: MemorySegment,
    ) {
        runCatching {
            if (id != SpaAbi.PARAM_PROPS) return
            val node = data.address().toInt()
            val held = nodes[node] ?: return
            val props = SpaPodReader.objectProperties(param)
            val channels = props[SpaAbi.PROP_CHANNEL_VOLUMES] as? FloatArray
            // The map rather than the volume array, because the two disagree
            // exactly where it matters. See SpaAbi.PROP_CHANNEL_MAP: a six
            // channel device reports six positions from the moment it appears
            // and two volumes until something writes one.
            val map = props[SpaAbi.PROP_CHANNEL_MAP] as? IntArray
            val volume = channels?.maxOrNull() ?: props[SpaAbi.PROP_VOLUME] as? Float
            val muted = props[SpaAbi.PROP_MUTE] as? Boolean
            if (volume == null && muted == null && map == null) return
            nodes[node] = held.copy(
                // What the node did not say keeps what it said last, because a
                // parameter arrives whole only the first time.
                volume = volume?.coerceIn(0f, 1f) ?: held.volume,
                muted = muted ?: held.muted,
                // The volume array is the fallback rather than the source: it
                // is the right count wherever the two agree, which is every
                // node whose channels were never narrowed.
                volumeChannels = map?.size ?: channels?.size ?: held.volumeChannels,
            )
            fire()
        }.onFailure { log.debug("node param threw: {}", it.message) }
    }

    /**
     * A node's own description of itself, of which one field is read: whether
     * the server has closed the hardware because nothing is using it.
     *
     * An ordinary resting state rather than a fault, and worth showing for the
     * reason [AudioDevice.isSuspended] gives: somebody looking at a silent
     * device wants to know which kind of silence it is.
     */
    fun onNodeInfo(data: MemorySegment, info: MemorySegment) {
        runCatching {
            if (info.address() == 0L) return
            val node = data.address().toInt()
            val held = nodes[node] ?: return
            val whole = info.reinterpret(SpaAbi.NODE_INFO_SIZE)
            val changed = whole.get(ValueLayout.JAVA_LONG, SpaAbi.NODE_INFO_CHANGE_MASK)
            val state = whole.get(ValueLayout.JAVA_INT, SpaAbi.NODE_INFO_STATE)
            val suspended = state == SpaAbi.NODE_STATE_SUSPENDED
            // Running rather than merely attached, which is what a mixer greys
            // a row for: a paused player holds its node open and renders
            // nothing, and the row belongs on screen either way.
            val running = state == SpaAbi.NODE_STATE_RUNNING
            // The node's own dict, which is a larger set than the registry
            // global's: an application's process id is on this one and not on
            // that one, so a mixer reading only the global could not tell which
            // rows belong to the process it is running in. Read only when the
            // event says it refreshed the pointer, and merged rather than
            // replacing, because an event that carried none should not empty
            // what the global already said.
            val refreshed = if (changed and SpaAbi.NODE_CHANGE_MASK_PROPS != 0L) {
                val dict = whole.get(ValueLayout.ADDRESS, SpaAbi.NODE_INFO_PROPS)
                if (dict.address() == 0L) emptyMap() else readDict(dict)
            } else {
                emptyMap()
            }
            if (suspended == held.suspended && running == held.running && refreshed.isEmpty()) return
            nodes[node] = held.copy(
                suspended = suspended,
                running = running,
                properties = if (refreshed.isEmpty()) held.properties else held.properties + refreshed,
            )
            fire()
        }.onFailure { log.debug("node info threw: {}", it.message) }
    }

    /**
     * A round trip coming back, which is the only thing this listens to the
     * core for.
     *
     * The server answers a sync after everything it had already queued, so this
     * arriving means the globals sent before it have been dispatched. That is
     * what a connect waits on instead of a clock.
     */
    fun onCoreDone(unusedData: MemorySegment, id: Int, seq: Int) {
        runCatching {
            if (id != SpaAbi.ID_CORE || seq != pendingSeq) return
            syncDone = true
            loop.signal()
        }
    }

    /**
     * A refused call on this connection.
     *
     * Bound so a barrier ends when the answer it is waiting for will not come.
     * Without it a connection the server has rejected costs the full timeout at
     * connect and says nothing about why.
     */
    fun onCoreError(
        unusedData: MemorySegment,
        id: Int,
        seq: Int,
        result: Int,
        message: MemorySegment,
    ) {
        runCatching {
            log.debug("the graph refused id {}: {} ({})", id, message.readCString(), result)
            // A refusal of the write a setter is waiting on, which is how that
            // setter answers false rather than reporting the request it sent.
            if (id == watchedProxy) proxyRefused = true
            // Only the request the barrier is waiting for ends it. Ending on
            // any core error at all would let an unrelated refusal, on another
            // object, cut the wait short and leave settle satisfied with a
            // device list the graph had not finished sending, silently.
            if (id != SpaAbi.ID_CORE || seq != pendingSeq) return
            syncFailed = true
            syncDone = true
            loop.signal()
        }
    }

    // -- internals ------------------------------------------------------------

    private fun defaultName(direction: StreamDirection): String? = when (direction) {
        StreamDirection.PLAYBACK -> defaultSinkName
        StreamDirection.CAPTURE -> defaultSourceName
    }

    private fun addNode(id: Int, entries: Map<String, String>) {
        val mediaClass = entries[SpaAbi.KEY_MEDIA_CLASS] ?: return
        val streaming = streamDirectionOf(mediaClass)
        val directions = streaming?.let { setOf(it) } ?: directionsOf(mediaClass)
        // A filter, a video node, or anything else the graph carries that is
        // neither a device nor somebody using one.
        if (directions.isEmpty()) return
        // node.name is the stable identity a target.object is named by, and
        // the description is what a person reads. Falling back to the name
        // is ugly and unique, which beats an empty row in a device menu.
        val name = entries[SpaAbi.KEY_NODE_NAME] ?: return
        val label = entries[SpaAbi.KEY_NODE_DESCRIPTION]
            ?: entries[SpaAbi.KEY_NODE_NICK]
            ?: entries[SpaAbi.KEY_DEVICE_DESCRIPTION]
            ?: name
        val serial = entries[SpaAbi.KEY_OBJECT_SERIAL]?.toLongOrNull() ?: NO_SERIAL
        nodes[id] = GraphNode(
            name = name,
            label = label,
            directions = directions,
            isStream = streaming != null,
            serial = serial,
            properties = entries,
        )
        // What a capture aimed at one application checks against. Playback only,
        // because recording something that is itself recording is not a thing
        // this offers.
        if (streaming == StreamDirection.PLAYBACK && serial != NO_SERIAL) playing[serial] = id
        bindNode(id)
        fire()
    }

    /**
     * One link, kept for the one question it answers.
     *
     * Both ends arrive on the global's own dict as decimal node ids, so this
     * needs no bind and no round trip, the same as a device row.
     */
    private fun addLink(id: Int, entries: Map<String, String>) {
        val output = entries[SpaAbi.KEY_LINK_OUTPUT_NODE]?.toIntOrNull() ?: return
        val input = entries[SpaAbi.KEY_LINK_INPUT_NODE]?.toIntOrNull() ?: return
        links[id] = output to input
        fire()
    }

    /**
     * The device at the far end of a stream's links, or null while it is
     * attached to nothing.
     *
     * Audio leaves a playback stream and enters a device, and enters a capture
     * stream having left one, so which end to follow depends on which way the
     * row flows. Several links carry one stream, one per channel, and they all
     * lead to the same node, so the first that lands on a device is the answer.
     *
     * Null is a real state rather than a gap: a stream the session manager has
     * not placed yet, or one whose target went away, is attached to nothing for
     * as long as that lasts.
     */
    private fun deviceOf(node: Int, direction: StreamDirection): DeviceId? {
        val far = links.values.asSequence().mapNotNull { (output, input) ->
            when {
                direction == StreamDirection.PLAYBACK && output == node -> input
                direction == StreamDirection.CAPTURE && input == node -> output
                else -> null
            }
        }
        return far.mapNotNull { nodes[it] }.firstOrNull { !it.isStream }?.let { DeviceId(it.name) }
    }

    /**
     * Which lists a `media.class` puts a device on.
     *
     * Prefix rather than equality, because the graph qualifies these: a
     * loopback microphone is `Audio/Source/Virtual`, and matching the bare name
     * whole leaves it out of the capture list on a machine that has one.
     * `Audio/Duplex` is one node on both lists.
     */
    private fun directionsOf(mediaClass: String): Set<StreamDirection> = when {
        mediaClass == SpaAbi.MEDIA_CLASS_DUPLEX ->
            setOf(StreamDirection.PLAYBACK, StreamDirection.CAPTURE)
        mediaClass.startsWith(SpaAbi.MEDIA_CLASS_SINK) -> setOf(StreamDirection.PLAYBACK)
        mediaClass.startsWith(SpaAbi.MEDIA_CLASS_SOURCE) -> setOf(StreamDirection.CAPTURE)
        else -> emptySet()
    }

    /**
     * Which way somebody's audio flows, or null where the node is not somebody
     * but something.
     *
     * The graph's own names read the opposite way round from a mixer's: a node
     * playing music is a `Stream/Output/Audio`, because the audio leaves it,
     * and a mixer calls that row playback. One is about the node's ports and the
     * other about the person looking at the screen.
     */
    private fun streamDirectionOf(mediaClass: String): StreamDirection? = when (mediaClass) {
        SpaAbi.MEDIA_CLASS_STREAM_OUTPUT -> StreamDirection.PLAYBACK
        SpaAbi.MEDIA_CLASS_STREAM_INPUT -> StreamDirection.CAPTURE
        else -> null
    }

    /**
     * Bind one audio node, and ask to be told about its properties.
     *
     * The rest of a device row travels on the global's own dict and needs no
     * proxy at all. Its volume does not: that is a parameter of the node, and a
     * parameter is only reachable through a bind. So an audio node gets one,
     * and nothing else on the graph does.
     *
     * The subscription is not a question. Asking is `enum_params`, which
     * answers once; this asks to be told again whenever the value changes, so a
     * slider somebody else moved arrives here as an event rather than being
     * discovered at the next re-read.
     *
     * The node's own global id goes across as the listener's data, which is
     * what the callback has to tell one node from another. It is a number
     * carried as a pointer and never dereferenced, and it is never zero,
     * because zero is the core.
     */
    private fun bindNode(id: Int) {
        if (id in nodeProxies) return
        val proxy = bind(id, nodeType, SpaAbi.VERSION_NODE)
        if (proxy.address() == 0L) return
        val gave = interfaceType(proxy)
        if (gave != SpaAbi.INTERFACE_NODE) {
            log.debug("bind of node {} answered a {}", id, gave)
            runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
            return
        }
        val hook = spareHooks.removeLastOrNull() ?: stubArena.allocate(SpaAbi.HOOK_SIZE, 8)
        hook.fill(0)
        nodeProxies[id] = proxy
        nodeHooks[id] = hook
        lib.handle("pw_proxy_add_object_listener")
            .invokeExact(proxy, hook, nodeEvents, MemorySegment.ofAddress(id.toLong())) as Unit
        runCatching { subscribeProps(proxy) }
            .onFailure { log.debug("subscribe_params on node {} threw: {}", id, it.message) }
    }

    /**
     * What the graph itself has said about one node's volume and mute, with
     * null for whichever of the two it has not said yet.
     *
     * Deliberately not the row a mixer draws. There a volume nobody has
     * reported shows as full, because a slider has to be somewhere, and that
     * substitution is harmless on screen and ruinous in a record of what to put
     * back: a value nobody measured, restored at close, is this process setting
     * a stranger's stream to a level they never chose.
     */
    data class NodeSettings(val volume: Float?, val muted: Boolean?)

    /** What the graph has said about one of the rows a mixer draws. */
    fun streamSettings(serial: Long): NodeSettings? =
        settingsOf { it.isStream && it.serial == serial }

    /** The same, for a device named the way a device list names it. */
    fun deviceSettings(name: String): NodeSettings? =
        settingsOf { !it.isStream && it.name == name }

    private fun settingsOf(match: (GraphNode) -> Boolean): NodeSettings? =
        nodes.values.firstOrNull(match)?.let { NodeSettings(it.volume, it.muted) }

    /**
     * Write a volume, a mute, or both onto one node, by the name it is listed
     * under.
     *
     * The other direction of the parameter this already subscribes to. A node
     * that has not answered with its own properties yet has no channel count to
     * write back, so it is refused rather than written with a guess: a two entry
     * array sent to a six channel device sets two of its channels and silently
     * leaves the rest.
     *
     * True means the graph took the request, not that the value stuck. A session
     * manager may have its own opinion a moment later, which is the same caveat
     * the libpulse side carries and the reason a caller that needs the value to
     * hold reads it back.
     */
    fun setDeviceProps(name: String, volume: Float?, muted: Boolean?): Boolean =
        setProps({ !it.isStream && it.name == name }, volume, muted)

    /** The same, for one of the rows a mixer draws, named by its serial. */
    fun setStreamProps(serial: Long, volume: Float?, muted: Boolean?): Boolean =
        setProps({ it.isStream && it.serial == serial }, volume, muted)

    private fun setProps(match: (GraphNode) -> Boolean, volume: Float?, muted: Boolean?): Boolean =
        calls.withLock {
            if (closed.get()) return@withLock false
            // Outside the loop lock rather than inside it, because taking that
            // lock is itself refused on a closed loop and `VolumeMixer` says a
            // control that cannot act answers false rather than throwing.
            runCatching {
                loop.locked {
                    val entry = nodes.entries.firstOrNull { match(it.value) } ?: return@locked false
                    val proxy = nodeProxies[entry.key] ?: return@locked false
                    val channels = entry.value.volumeChannels
                    if (volume != null && channels <= 0) return@locked false
                    val pod = SpaPod.props(
                        channelVolumes = volume?.let { level -> FloatArray(channels) { level.coerceIn(0f, 1f) } },
                        mute = muted,
                    )
                    runCatching { answered(proxy) { setParam(proxy, SpaAbi.PARAM_PROPS, pod) } }
                        .onFailure { log.debug("set_param on {} threw: {}", entry.value.name, it.message) }
                        .getOrDefault(false)
                }
            }.getOrDefault(false)
        }

    /**
     * Make [name] the device applications get when they ask for none.
     *
     * Written into the entry that records a choice rather than the one that
     * records the result: the session manager computes the second from the
     * first, so writing the result directly is writing a value the next rescan
     * replaces.
     */
    fun setDefaultDevice(name: String, direction: StreamDirection): Boolean {
        val key = when (direction) {
            StreamDirection.PLAYBACK -> SpaAbi.METADATA_KEY_CONFIGURED_SINK
            StreamDirection.CAPTURE -> SpaAbi.METADATA_KEY_CONFIGURED_SOURCE
        }
        // The same shape the session manager writes, and the reader above takes
        // it back apart.
        return setMetadata(SpaAbi.METADATA_SUBJECT_GRAPH, key, SpaAbi.METADATA_TYPE_JSON, "{\"name\":\"$name\"}")
    }

    /**
     * Move one stream onto one device.
     *
     * A property on the stream rather than a link made by hand: the session
     * manager owns linking, reads this and relinks. Making the links here
     * instead would be a second policy running beside the one the desktop
     * already has, and the two would disagree the first time anything else
     * moved.
     */
    fun moveStream(serial: Long, device: String): Boolean {
        val node = nodes.entries.firstOrNull { it.value.isStream && it.value.serial == serial } ?: return false
        return setMetadata(node.key, SpaAbi.METADATA_KEY_TARGET_OBJECT, null, device)
    }

    /**
     * Make a device the machine does not have, and hold the proxy that owns it.
     *
     * `create_object` on the core, with the adapter factory and the null sink
     * behind it, which is the pair the daemon's own shipped configuration names.
     *
     * `object.linger` is deliberately not set. The header describes that key as
     * the one that makes an object outlive its client, so leaving it out ties
     * what comes back to this connection. That is not a shortfall against the
     * module the libpulse mixer loads: the obligation
     * `VolumeMixer.createVirtualSink` is written under is that a device left
     * behind is one a person finds in their settings and cannot account for,
     * and a connection-scoped object is the shape that obligation wants.
     *
     * The channel count is honoured or the whole thing is refused, never
     * narrowed. The positions travel with it where every one of them has a name
     * here, and where they do not the count goes on its own and the graph lays
     * it out, which is what a device with no layout gets anywhere.
     */
    fun createNullSink(name: String, channels: Int): Boolean {
        if (channels !in 1..SpaAbi.MAX_CHANNELS) return false
        val layout = ChannelLayout.defaultFor(channels)
        val positions = layout.positions.map { SpaAbi.channelNameOf(it) }
        val entries = buildList {
            add(SpaAbi.KEY_FACTORY_NAME to SpaAbi.FACTORY_NULL_SINK)
            add(SpaAbi.KEY_NODE_NAME to name)
            add(SpaAbi.KEY_NODE_DESCRIPTION to name)
            add(SpaAbi.KEY_MEDIA_CLASS to SpaAbi.MEDIA_CLASS_SINK)
            add(SpaAbi.KEY_AUDIO_CHANNELS to channels.toString())
            if (positions.isNotEmpty() && positions.none { it == null }) {
                add(SpaAbi.KEY_AUDIO_POSITION to positions.joinToString(",") { checkNotNull(it) })
            }
        }
        return calls.withLock {
            if (closed.get()) return@withLock false
            runCatching {
                loop.locked {
                    // Asked and answered under the one lock that guards the
                    // map. Split in two, two callers naming the same device
                    // both get past the question and the second proxy replaces
                    // the first in the map, leaving a device on the graph that
                    // nothing here can destroy any more.
                    if (createdSinks.containsKey(name)) return@locked false
                    val proxy = createObject(SpaAbi.FACTORY_ADAPTER, nodeType, SpaAbi.VERSION_NODE, entries)
                    if (proxy.address() == 0L) return@locked false
                    createdSinks[name] = proxy
                    true
                }
            }.onFailure { log.debug("create_object({}) threw: {}", name, it.message) }
                .getOrDefault(false)
        }
    }

    /** Drop a device this connection made. False for one it did not make. */
    fun removeNullSink(name: String): Boolean = calls.withLock {
        if (closed.get()) return@withLock false
        runCatching {
            loop.locked {
                val proxy = createdSinks.remove(name) ?: return@locked false
                runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
                    .onFailure { log.debug("destroy of {} threw: {}", name, it.message) }
                    .isSuccess
            }
        }.getOrDefault(false)
    }

    /**
     * Every device this connection made and has not removed.
     *
     * Under the loop lock like every other reader of that map, because the
     * caller is a restore running on somebody else's thread while the loop's
     * own thread may be adding to it.
     */
    fun createdSinkNames(): List<String> = calls.withLock {
        if (closed.get()) return@withLock emptyList()
        runCatching { loop.locked { createdSinks.keys.toList() } }.getOrDefault(emptyList())
    }

    /**
     * `pw_core_create_object`, walked like every other proxy method here.
     *
     * The loop lock must be held. The property strings live only for the call,
     * which is right here and was not right for a bind: `pw_proxy_new` keeps
     * the type string it is handed, and a dict is marshalled onto the wire
     * before this returns.
     */
    private fun createObject(
        factory: String,
        type: MemorySegment,
        version: Int,
        entries: List<Pair<String, String>>,
    ): MemorySegment {
        val method = interfaceMethod(core, SpaAbi.CORE_METHOD_CREATE_OBJECT, "create_object")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ),
        )
        return Arena.ofConfined().use { call2 ->
            val items = call2.allocate(SpaAbi.DICT_ITEM_SIZE * entries.size, 8)
            entries.forEachIndexed { index, (key, value) ->
                val at = SpaAbi.DICT_ITEM_SIZE * index
                items.set(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_KEY, call2.allocateFrom(key))
                items.set(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_VALUE, call2.allocateFrom(value))
            }
            val dict = call2.allocate(SpaAbi.DICT_SIZE, 8)
            dict.set(ValueLayout.JAVA_INT, SpaAbi.DICT_FLAGS, 0)
            dict.set(ValueLayout.JAVA_INT, SpaAbi.DICT_N_ITEMS, entries.size)
            dict.set(ValueLayout.ADDRESS, SpaAbi.DICT_ITEMS, items)
            call.invokeExact(
                interfaceData(core), call2.allocateFrom(factory), type, version, dict, 0L,
            ) as MemorySegment
        }
    }

    /**
     * `pw_metadata_set_property`, walked for the reason every other proxy
     * method here is.
     *
     * False where nothing is bound, which is a graph with no session manager on
     * it: there is no object to write into and no policy that would read it.
     */
    private fun setMetadata(subject: Int, key: String, type: String?, value: String?): Boolean =
        calls.withLock {
            if (closed.get()) return@withLock false
            runCatching {
                loop.locked {
                    val proxy = metadata
                    if (proxy.address() == 0L) return@locked false
                    val method =
                        interfaceMethod(proxy, SpaAbi.METADATA_METHOD_SET_PROPERTY, "set_property")
                    val call = Linker.nativeLinker().downcallHandle(
                        method,
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ),
                    )
                    answered(proxy) {
                        Arena.ofConfined().use { call2 ->
                            // The strings are read during the call and
                            // marshalled onto the wire, so a confined arena is
                            // the right lifetime here where a bind's type
                            // string was not.
                            val rc = call.invokeExact(
                                interfaceData(proxy), subject,
                                call2.allocateFrom(key),
                                type?.let { call2.allocateFrom(it) } ?: MemorySegment.NULL,
                                value?.let { call2.allocateFrom(it) } ?: MemorySegment.NULL,
                            ) as Int
                            rc >= 0
                        }
                    }
                }
            }.onFailure { log.debug("set_property({}) threw: {}", key, it.message) }
                .getOrDefault(false)
        }

    /**
     * Make one write and come back with what the graph did about it, rather
     * than with whether it was sent.
     *
     * `VolumeMixer` asks its setters for the server's answer, because a row
     * that springs back is the correct rendering of a stream that closed
     * mid-drag and nothing else can draw it. A proxy method gives no answer of
     * its own, so the answer is assembled: the write, then a sync, which the
     * server replies to only after everything queued ahead of it. A refusal of
     * the write is one of those things, and it names this proxy.
     *
     * The round trip is one message each way on a local socket. It is also what
     * makes the value readable the moment this returns, because the parameter
     * event carrying it back is queued ahead of the sync as well.
     *
     * Both [calls] and the loop lock must be held.
     */
    private fun answered(proxy: MemorySegment, write: () -> Boolean): Boolean {
        watchedProxy = proxyId(proxy)
        proxyRefused = false
        try {
            if (!write()) return false
            // A graph that did not answer the sync is one this cannot speak
            // for, which is the same false a refusal gives.
            if (!roundTripLocked()) return false
            return !proxyRefused
        } finally {
            watchedProxy = NO_PROXY
        }
    }

    /** The id the connection gave a proxy, which is what an error names. */
    private fun proxyId(proxy: MemorySegment): Int =
        lib.handle("pw_proxy_get_id").invokeExact(proxy) as Int

    /**
     * `pw_node_set_param`, walked for the reason every other proxy method here
     * is. The loop lock must be held.
     */
    private fun setParam(proxy: MemorySegment, paramId: Int, pod: ByteArray): Boolean {
        val method = interfaceMethod(proxy, SpaAbi.NODE_METHOD_SET_PARAM, "set_param")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
        )
        return Arena.ofConfined().use { call2 ->
            val segment = call2.allocate(pod.size.toLong(), SpaAbi.POD_ALIGN.toLong())
            MemorySegment.copy(pod, 0, segment, ValueLayout.JAVA_BYTE, 0L, pod.size)
            (call.invokeExact(interfaceData(proxy), paramId, 0, segment) as Int) >= 0
        }
    }

    /** Drop one node's proxy and put its hook back. The loop lock must be held. */
    private fun releaseNode(id: Int) {
        val proxy = nodeProxies.remove(id) ?: return
        // The hook is reusable once the proxy holding it is gone, and
        // pw_proxy_add_object_listener overwrites its fields on the next bind.
        // Recycled rather than allocated afresh, so a machine where devices
        // come and go all day does not grow an arena a hook at a time.
        nodeHooks.remove(id)?.let { spareHooks.addLast(it) }
        runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
            .onFailure { log.debug("node proxy destroy threw: {}", it.message) }
    }

    /**
     * `pw_node_subscribe_params` for the one parameter this reads, walked for
     * the reason every other proxy method here is.
     */
    private fun subscribeProps(proxy: MemorySegment) {
        val method = interfaceMethod(proxy, SpaAbi.NODE_METHOD_SUBSCRIBE_PARAMS, "subscribe_params")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ),
        )
        call.invokeExact(interfaceData(proxy), propsParam, 1) as Int
    }

    /**
     * Bind the metadata object that holds the defaults, and nothing else.
     *
     * The graph carries several: `settings`, `sm-objects`, `default-profile`
     * and this one, told apart only by `metadata.name`. One is bound at a time,
     * so a second object claiming the name is left alone rather than replacing
     * a working listener with a race.
     *
     * The loop lock is held here, because this runs on the loop's own thread
     * inside a dispatch, which is exactly the condition the whole of
     * [openOrNull] is arranged to guarantee: the object is asked for and its
     * listener attached without the lock being released in between, so the
     * burst of properties the server sends back cannot arrive before anything
     * is listening.
     */
    private fun bindDefaults(id: Int, entries: Map<String, String>) {
        if (entries[SpaAbi.KEY_METADATA_NAME] != SpaAbi.METADATA_DEFAULT) return
        if (metadataId != NO_GLOBAL) return
        val proxy = bind(id, metadataType, SpaAbi.VERSION_METADATA)
        if (proxy.address() == 0L) {
            log.debug("bind of the default metadata answered null")
            return
        }
        // What came back says what it is, and a listener is only worth
        // attaching to something that says metadata. A proxy of another
        // interface would take the listener and call its one slot with five
        // arguments that are not the ones this reads.
        val gave = interfaceType(proxy)
        if (gave != SpaAbi.INTERFACE_METADATA) {
            log.debug("bind of the default metadata answered a {}", gave)
            runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
            return
        }
        metadata = proxy
        metadataId = id
        lib.handle("pw_proxy_add_object_listener")
            .invokeExact(proxy, metadataHook, metadataEvents, MemorySegment.NULL) as Unit
    }

    /** Drop the bound proxy. The loop lock must be held. */
    private fun releaseMetadata() {
        val proxy = metadata
        metadata = MemorySegment.NULL
        metadataId = NO_GLOBAL
        if (proxy.address() == 0L) return
        runCatching { lib.handle("pw_proxy_destroy").invokeExact(proxy) as Unit }
            .onFailure { log.debug("metadata proxy destroy threw: {}", it.message) }
    }

    /**
     * `pw_registry_bind`, which is a macro over the registry's method table for
     * the reason `pw_core_get_registry` is, and walked the same way.
     *
     * The type string is allocated for the life of this object rather than for
     * the call: `pw_proxy_new` keeps the pointer it is handed instead of
     * copying the string, so a confined arena here would leave every bound
     * proxy naming freed memory.
     */
    private fun bind(id: Int, type: MemorySegment, version: Int): MemorySegment {
        val method = interfaceMethod(registry, SpaAbi.REGISTRY_METHOD_BIND, "bind")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
            ),
        )
        return call.invokeExact(interfaceData(registry), id, type, version, 0L) as MemorySegment
    }

    /** Allocated once and kept, for the reason [bind] gives. */
    private val metadataType: MemorySegment by lazy {
        stubArena.allocateFrom(SpaAbi.INTERFACE_METADATA)
    }

    private val nodeType: MemorySegment by lazy {
        stubArena.allocateFrom(SpaAbi.INTERFACE_NODE)
    }

    /** The one parameter id a subscription names, in memory a call can point at. */
    private val propsParam: MemorySegment by lazy {
        stubArena.allocate(ValueLayout.JAVA_INT, 1).apply {
            set(ValueLayout.JAVA_INT, 0L, SpaAbi.PARAM_PROPS)
        }
    }

    /**
     * A `spa_dict` into a map.
     *
     * Bounded by the count the struct carries rather than walked to a
     * terminator, because there is not one: the dict is a length and an array,
     * and reading past the length is reading whatever the graph allocated next.
     */
    private fun readDict(dict: MemorySegment): Map<String, String> {
        val head = dict.reinterpret(SpaAbi.DICT_SIZE)
        val count = head.get(ValueLayout.JAVA_INT, SpaAbi.DICT_N_ITEMS)
        if (count <= 0 || count > MAX_DICT_ITEMS) return emptyMap()
        val items = head.get(ValueLayout.ADDRESS, SpaAbi.DICT_ITEMS)
        if (items.address() == 0L) return emptyMap()
        val sized = items.reinterpret(SpaAbi.DICT_ITEM_SIZE * count)
        val entries = HashMap<String, String>(count * 2)
        for (index in 0 until count) {
            val at = SpaAbi.DICT_ITEM_SIZE * index
            val key = sized.get(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_KEY).readCString() ?: continue
            val value = sized.get(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_VALUE).readCString() ?: continue
            entries[key] = value
        }
        return entries
    }

    private fun fire() {
        val handlers = listeners.toList()
        if (handlers.isEmpty()) return
        runCatching {
            dispatch.execute {
                handlers.forEach { handler ->
                    runCatching { handler() }.onFailure { log.warn("device listener threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("device event dropped, the registry is closing") }
    }

    private fun installListener() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT

        val events = stubArena.allocate(SpaAbi.REGISTRY_EVENTS_SIZE, 8)
        events.fill(0)
        events.set(ValueLayout.JAVA_INT, SpaAbi.REGISTRY_EVENTS_VERSION, SpaAbi.VERSION_REGISTRY_EVENTS)
        events.set(
            addr, SpaAbi.REGISTRY_EVENTS_GLOBAL,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onGlobal",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        MemorySegment::class.java, Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, i32, addr, i32, addr),
                stubArena,
            ),
        )
        events.set(
            addr, SpaAbi.REGISTRY_EVENTS_GLOBAL_REMOVE,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onGlobalRemove",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32),
                stubArena,
            ),
        )
        // The hook belongs to the caller and has to outlive the listener, which
        // is why it comes out of the arena rather than off a stack.
        val hook = stubArena.allocate(SpaAbi.HOOK_SIZE, 8)
        hook.fill(0)
        // The caller holds the loop lock, and it has held it since before the
        // registry was asked for. See [openOrNull].
        lib.handle("pw_proxy_add_object_listener")
            .invokeExact(registry, hook, events, MemorySegment.NULL) as Unit
        // Built here rather than at the first global that needs one, so the
        // stubs are linked while nothing is waiting on them: the alternative
        // pays for a method handle lookup inside a dispatch on the loop's own
        // thread, with the burst of every object on the graph behind it.
        metadataEvents
        metadataHook
        nodeEvents
        nodeType
        propsParam
    }

    /**
     * The metadata object's events, which is one slot and eight bytes of
     * version in front of it.
     *
     * Reused across a rebind rather than allocated per one, and the hook with
     * it: `pw_proxy_add_object_listener` overwrites the hook's fields, and a
     * proxy that has been destroyed holds no reference to it.
     */
    private val metadataEvents: MemorySegment by lazy {
        val struct = stubArena.allocate(SpaAbi.METADATA_EVENTS_SIZE, 8)
        struct.fill(0)
        struct.set(ValueLayout.JAVA_INT, SpaAbi.METADATA_EVENTS_VERSION, SpaAbi.VERSION_METADATA_EVENTS)
        struct.set(
            ValueLayout.ADDRESS, SpaAbi.METADATA_EVENTS_PROPERTY,
            Linker.nativeLinker().upcallStub(
                MethodHandles.lookup().findVirtual(
                    PipeWireRegistry::class.java, "onMetadataProperty",
                    MethodType.methodType(
                        Int::class.javaPrimitiveType, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, MemorySegment::class.java,
                        MemorySegment::class.java, MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
                stubArena,
            ),
        )
        struct
    }

    private val metadataHook: MemorySegment by lazy {
        stubArena.allocate(SpaAbi.HOOK_SIZE, 8).apply { fill(0) }
    }

    /**
     * One events struct shared by every bound node, which is what the data
     * pointer is for: the struct says what to call and the pointer says which
     * node it is about.
     */
    private val nodeEvents: MemorySegment by lazy {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT

        val struct = stubArena.allocate(SpaAbi.NODE_EVENTS_SIZE, 8)
        struct.fill(0)
        struct.set(ValueLayout.JAVA_INT, SpaAbi.NODE_EVENTS_VERSION, SpaAbi.VERSION_NODE_EVENTS)
        struct.set(
            addr, SpaAbi.NODE_EVENTS_INFO,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onNodeInfo",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, addr),
                stubArena,
            ),
        )
        struct.set(
            addr, SpaAbi.NODE_EVENTS_PARAM,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onNodeParam",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, i32, i32, i32, addr),
                stubArena,
            ),
        )
        struct
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /** A graph with more objects than this is one something is wrong with. */
        private const val MAX_DICT_ITEMS = 4_096

        /** No global id, which is distinct from every real one because they start at 0. */
        private const val NO_GLOBAL = -1

        /** No sequence outstanding. Real ones are assigned by the protocol and positive. */
        private const val NO_SEQ = -1

        /** No write outstanding. Real proxy ids are handed out by the connection from zero up. */
        private const val NO_PROXY = -1

        /** No serial. Real ones are assigned by the graph and positive. */
        private const val NO_SERIAL = 0L

        /**
         * How long a connect waits for the graph to answer a sync.
         *
         * A ceiling on a case that should not happen rather than a duration
         * anything normally costs: a local socket answers in single-digit
         * milliseconds, and reaching this means a server that accepted a
         * request and will not reply to it.
         */
        private const val ROUND_TRIP_SECONDS = 2

        /** Open a connection of its own and start watching, or null where there is no graph. */
        fun openOrNull(applicationName: String): PipeWireRegistry? {
            val loop = PipeWireLoop.startOrNull("$applicationName registry") ?: return null
            val lib = loop.lib
            var context = MemorySegment.NULL
            var core = MemorySegment.NULL
            return runCatching {
                // One lock across the whole of construction, and this is the
                // load-bearing part of it rather than tidiness.
                //
                // Asking for the registry is what makes the server send one
                // global for everything already on the graph, and the loop
                // dispatches those as soon as it can take the lock. Released
                // between the ask and the listener, the entire opening burst
                // arrives before anything is listening and is gone: the device
                // list then stays empty on a machine with devices, and fills
                // only if something new appears. Measured, on a graph with one
                // sink that never showed up.
                val instance = loop.locked {
                    context = lib.handle("pw_context_new")
                        .invokeExact(loop.loop, MemorySegment.NULL, 0L) as MemorySegment
                    check(context.address() != 0L) { "pw_context_new failed" }
                    core = lib.handle("pw_context_connect")
                        .invokeExact(context, MemorySegment.NULL, 0L) as MemorySegment
                    check(core.address() != 0L) { "pw_context_connect failed" }
                    val registry = getRegistry(lib, core)
                    PipeWireRegistry(loop, context, core, registry).apply {
                        installCoreListener()
                        installListener()
                    }
                }
                // Then wait for the graph to say it has finished, outside the
                // lock, so the first devices() call sees the graph rather than
                // the start of it.
                instance.settle()
                instance
            }.getOrElse {
                log.debug("no PipeWire registry: {}", it.message)
                runCatching {
                    loop.locked {
                        if (core.address() != 0L) lib.handle("pw_core_disconnect").invokeExact(core) as Int
                        if (context.address() != 0L) lib.handle("pw_context_destroy").invokeExact(context) as Unit
                    }
                }
                loop.close()
                null
            }
        }

        /**
         * `pw_core_get_registry`, which is a macro over the core's method
         * table and therefore walked rather than bound.
         *
         * Every offset comes from `tools/pipewire-oracle.c`, for the reason the
         * WASAPI slot indices do, and the failure when one is wrong is the
         * same: a call through a function that is not the one meant.
         */
        private fun getRegistry(lib: PipeWireLibrary, core: MemorySegment): MemorySegment {
            // A sanity check the walk can actually make: offset zero of a
            // spa_interface is the name of the interface it is. A pointer that
            // is not one answers with something else or with nothing, and
            // finding that out here beats calling through a table that is not
            // a table.
            val declared = interfaceType(core)
            check(declared == SpaAbi.INTERFACE_CORE) {
                "the core does not declare itself a core: $declared"
            }
            val method = interfaceMethod(core, SpaAbi.CORE_METHOD_GET_REGISTRY, "get_registry")
            val call = Linker.nativeLinker().downcallHandle(
                method,
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ),
            )
            // The caller holds the loop lock.
            val registry = call.invokeExact(interfaceData(core), SpaAbi.VERSION_REGISTRY, 0L) as MemorySegment
            check(registry.address() != 0L) { "get_registry answered null" }
            // The same check on the way back out. What get_registry hands over
            // is walked in turn, so a pointer that is not a registry is worth
            // finding here rather than at the first bind.
            val gave = interfaceType(registry)
            check(gave == SpaAbi.INTERFACE_REGISTRY) {
                "get_registry did not answer a registry: $gave"
            }
            return registry
        }
    }

    /**
     * Wait until the graph has finished telling us what is on it.
     *
     * Two round trips, and the second was measured to be load bearing rather
     * than assumed to be. The first is answered after every global already on
     * the graph has been dispatched, which is what makes the device list
     * complete rather than probably complete. Those dispatches are where the
     * metadata object is found and bound, and a bind is a request in its own
     * right, so what it brings back is behind a sync of its own: on a graph
     * with one sink, the default read after the first sync was null and after
     * the second was the sink.
     *
     * This is what a sleep used to be, and the difference is not the duration.
     * A sleep long enough for a quiet machine is a coin toss on a loaded one,
     * and what it loses when it loses is a device list reported as empty by a
     * backend that says it can enumerate.
     */
    private fun settle() {
        if (!roundTrip()) {
            log.debug("the graph did not answer the first sync; the device list may be short")
            return
        }
        if (!roundTrip()) {
            log.debug("the graph did not answer the second sync; the default may be unknown")
        }
    }

    /**
     * One sync, waited out. False when the graph did not answer within
     * [ROUND_TRIP_SECONDS], which is a graph to report rather than one to keep
     * waiting on.
     */
    private fun roundTrip(): Boolean = calls.withLock { loop.locked { roundTripLocked() } }

    /** The wait itself, for a caller already holding both locks. */
    private fun roundTripLocked(): Boolean {
        val method = interfaceMethod(core, SpaAbi.CORE_METHOD_SYNC, "sync")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ),
        )
        syncDone = false
        syncFailed = false
        // The sequence the server will answer with is the one sync returns, not
        // the one it was handed: the protocol assigns its own and the two are
        // only equal by accident.
        pendingSeq = call.invokeExact(interfaceData(core), SpaAbi.ID_CORE, 0) as Int
        val deadline = System.nanoTime() + ROUND_TRIP_SECONDS * NANOS_PER_SECOND
        while (!syncDone) {
            if (System.nanoTime() >= deadline) return false
            // A spurious wake returns with nothing having happened, which is
            // why the flag is re-read rather than the wake being trusted.
            if (!loop.awaitFor(ROUND_TRIP_SECONDS)) return false
        }
        return !syncFailed
    }

    /**
     * The core's events, of which two slots are filled.
     *
     * Attached to the core the way the registry's are attached to the registry.
     * That this works on a core at all was measured against a live graph rather
     * than assumed: a core carries a second listener list that a different call
     * reaches, and reading the header alone would leave which of the two
     * delivers `done` a guess.
     */
    private fun installCoreListener() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT

        val events = stubArena.allocate(SpaAbi.CORE_EVENTS_SIZE, 8)
        events.fill(0)
        events.set(ValueLayout.JAVA_INT, SpaAbi.CORE_EVENTS_VERSION, SpaAbi.VERSION_CORE_EVENTS)
        events.set(
            addr, SpaAbi.CORE_EVENTS_DONE,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onCoreDone",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, i32),
                stubArena,
            ),
        )
        events.set(
            addr, SpaAbi.CORE_EVENTS_ERROR,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireRegistry::class.java, "onCoreError",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, i32, i32, addr),
                stubArena,
            ),
        )
        val hook = stubArena.allocate(SpaAbi.HOOK_SIZE, 8)
        hook.fill(0)
        lib.handle("pw_proxy_add_object_listener")
            .invokeExact(core, hook, events, MemorySegment.NULL) as Unit
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * What a proxy declares itself to be, which is offset zero of the
 * `spa_interface` every proxy pointer is.
 */
private fun interfaceType(proxy: MemorySegment): String? =
    proxy.reinterpret(SpaAbi.INTERFACE_SIZE).get(ValueLayout.ADDRESS, SpaAbi.INTERFACE_TYPE).readCString()

/**
 * The interface's own callback data, which is the first argument every method
 * on it takes.
 *
 * Measured to be the proxy itself on every proxy pipewire hands out, so this is
 * following the macro rather than correcting a bug that exists today. It is
 * read rather than assumed because nothing promises the two keep coinciding.
 */
private fun interfaceData(proxy: MemorySegment): MemorySegment =
    proxy.reinterpret(SpaAbi.INTERFACE_SIZE).get(ValueLayout.ADDRESS, SpaAbi.INTERFACE_CB_DATA)

/**
 * One method out of a proxy's table, by the offset the oracle printed.
 *
 * The same discipline the WASAPI vtable indices are held to, and the same
 * failure when an offset is wrong: a call through a function that is not the
 * one meant. [name] is in the message rather than in a comment because that
 * message is what somebody reads when a pipewire release moves a slot.
 */
private fun interfaceMethod(proxy: MemorySegment, offset: Long, name: String): MemorySegment {
    val methods = proxy.reinterpret(SpaAbi.INTERFACE_SIZE).get(ValueLayout.ADDRESS, SpaAbi.INTERFACE_CB_FUNCS)
    check(methods.address() != 0L) { "the proxy carries no method table, looking for $name" }
    val method = methods.reinterpret(offset + Long.SIZE_BYTES).get(ValueLayout.ADDRESS, offset)
    check(method.address() != 0L) { "the method table has no $name at $offset" }
    return method
}

/**
 * The node name out of the `{"name":"..."}` a default is written as.
 *
 * Not a JSON parser and not worth one: the value has a single string field in
 * it, and a shape this does not recognise answers null, which reads as no
 * default rather than as the wrong one. What it does handle is the whitespace,
 * because the session manager writes the configured entry spaced and the
 * effective one packed, and both arrive here.
 */
private fun nameIn(json: String): String? {
    val key = json.indexOf(NAME_FIELD)
    if (key < 0) return null
    val colon = json.indexOf(':', key + NAME_FIELD.length)
    if (colon < 0) return null
    val open = json.indexOf('"', colon + 1)
    if (open < 0) return null
    val name = StringBuilder()
    var index = open + 1
    while (index < json.length) {
        val character = json[index]
        when {
            character == '\\' && index + 1 < json.length -> {
                name.append(json[index + 1])
                index += 2
            }
            character == '"' -> return name.toString().ifEmpty { null }
            else -> {
                name.append(character)
                index += 1
            }
        }
    }
    return null
}

private const val NAME_FIELD = "\"name\""
