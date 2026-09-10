package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.StreamDirection
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
 * ## The one object it binds
 *
 * A global's properties come with the event, so a list needs no proxy. Which
 * device is default is the exception and is not a property of the graph at all:
 * the session manager writes it into a metadata object, so reading it means
 * binding that object and listening to it. That is one bind, of one global,
 * chosen by name, and it is the whole of what this holds a proxy for.
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
     * Every audio node the graph has told us about, by its global id.
     *
     * Written on the loop thread by the events and read by whoever asks, which
     * is what makes it concurrent rather than guarded: a device list is a
     * snapshot and a consumer that wants to know about a change subscribes.
     */
    private val nodes = ConcurrentHashMap<Int, AudioDevice>()

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

    /** The sequence number the barrier is waiting for, and whether it arrived. */
    @Volatile
    private var pendingSeq = NO_SEQ

    @Volatile
    private var syncDone = false

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
            .filter { it.direction == direction }
            .map { if (it.id.value == default) it.copy(isDefault = true) else it }
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
        return nodes.values.firstOrNull { it.direction == direction && it.id.value == default }
            ?.copy(isDefault = true)
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
        runCatching {
            loop.locked {
                // The bound proxy before the registry that handed it out, and
                // both before the connection they travelled on.
                releaseMetadata()
                lib.handle("pw_proxy_destroy").invokeExact(registry) as Unit
                lib.handle("pw_core_disconnect").invokeExact(core) as Int
                lib.handle("pw_context_destroy").invokeExact(context) as Unit
            }
        }.onFailure { log.warn("registry teardown threw: {}", it.message) }
        // The loop is stopped inside this, and only then is the arena released:
        // no upcall can be in flight past a stopped loop.
        loop.close()
        runCatching { stubArena.close() }
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
        unusedSeq: Int,
        result: Int,
        message: MemorySegment,
    ) {
        runCatching {
            log.debug("the graph refused id {}: {} ({})", id, message.readCString(), result)
            if (id != SpaAbi.ID_CORE) return
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
        val direction = when (entries[SpaAbi.KEY_MEDIA_CLASS]) {
            SpaAbi.MEDIA_CLASS_SINK -> StreamDirection.PLAYBACK
            SpaAbi.MEDIA_CLASS_SOURCE -> StreamDirection.CAPTURE
            // Everything else is a stream, a filter or a video node, and a
            // device menu offering one of those is a menu with a broken row.
            else -> return
        }
        // node.name is the stable identity a target.object is named by, and
        // the description is what a person reads. Falling back to the name
        // is ugly and unique, which beats an empty row in a device menu.
        val name = entries[SpaAbi.KEY_NODE_NAME] ?: return
        val label = entries[SpaAbi.KEY_NODE_DESCRIPTION]
            ?: entries[SpaAbi.KEY_NODE_NICK]
            ?: entries[SpaAbi.KEY_DEVICE_DESCRIPTION]
            ?: name
        nodes[id] = AudioDevice(id = DeviceId(name), name = label, direction = direction)
        fire()
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
        // Built here rather than at the first metadata global, so the stub is
        // linked while nothing is waiting on it: the alternative pays for a
        // method handle lookup inside a dispatch on the loop's thread.
        metadataEvents
        metadataHook
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

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /** A graph with more objects than this is one something is wrong with. */
        private const val MAX_DICT_ITEMS = 4_096

        /** No global id, which is distinct from every real one because they start at 0. */
        private const val NO_GLOBAL = -1

        /** No sequence outstanding. Real ones are assigned by the protocol and positive. */
        private const val NO_SEQ = -1

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
    private fun roundTrip(): Boolean = loop.locked {
        val method = interfaceMethod(core, SpaAbi.CORE_METHOD_SYNC, "sync")
        val call = Linker.nativeLinker().downcallHandle(
            method,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ),
        )
        syncDone = false
        // The sequence the server will answer with is the one sync returns, not
        // the one it was handed: the protocol assigns its own and the two are
        // only equal by accident.
        pendingSeq = call.invokeExact(interfaceData(core), SpaAbi.ID_CORE, 0) as Int
        val deadline = System.nanoTime() + ROUND_TRIP_SECONDS * NANOS_PER_SECOND
        while (!syncDone) {
            if (System.nanoTime() >= deadline) return@locked false
            // A spurious wake returns with nothing having happened, which is
            // why the flag is re-read rather than the wake being trusted.
            if (!loop.awaitFor(ROUND_TRIP_SECONDS)) return@locked false
        }
        true
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
