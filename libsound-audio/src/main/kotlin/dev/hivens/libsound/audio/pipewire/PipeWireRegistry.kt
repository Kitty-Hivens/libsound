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
 * ## What it does not do
 *
 * Bind anything. A global's properties come with the event, so a list needs no
 * proxy; the default device and a node's volume do need one, and both are named
 * in section 13 as the next thing rather than left as a surprise. Reaching them
 * means walking a proxy's method table by hand, because the headers call it
 * through a macro, and `SpaAbi` carries the offsets the day that is written.
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

    /** Holds the events struct, its stubs, and the hook the listener is registered through. */
    private val stubArena: Arena = Arena.ofShared()

    fun devices(direction: StreamDirection): List<AudioDevice> =
        nodes.values.filter { it.direction == direction }.sortedBy { it.name }

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
     * One object on the graph. Only audio nodes are kept, and only the two
     * classes that are devices rather than somebody playing through one.
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
            if (type.readCString() != SpaAbi.INTERFACE_NODE) return@runCatching
            if (props.address() == 0L) return@runCatching
            val entries = readDict(props)
            val direction = when (entries[SpaAbi.KEY_MEDIA_CLASS]) {
                SpaAbi.MEDIA_CLASS_SINK -> StreamDirection.PLAYBACK
                SpaAbi.MEDIA_CLASS_SOURCE -> StreamDirection.CAPTURE
                // Everything else is a stream, a filter or a video node, and a
                // device menu offering one of those is a menu with a broken row.
                else -> return@runCatching
            }
            // node.name is the stable identity a target.object is named by, and
            // the description is what a person reads. Falling back to the name
            // is ugly and unique, which beats an empty row in a device menu.
            val name = entries[SpaAbi.KEY_NODE_NAME] ?: return@runCatching
            val label = entries[SpaAbi.KEY_NODE_DESCRIPTION]
                ?: entries[SpaAbi.KEY_NODE_NICK]
                ?: entries[SpaAbi.KEY_DEVICE_DESCRIPTION]
                ?: name
            nodes[id] = AudioDevice(id = DeviceId(name), name = label, direction = direction)
            fire()
        }.onFailure { log.debug("registry global threw: {}", it.message) }
    }

    fun onGlobalRemove(unusedData: MemorySegment, id: Int) {
        runCatching {
            if (nodes.remove(id) != null) fire()
        }
    }

    // -- internals ------------------------------------------------------------

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
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /** A graph with more objects than this is one something is wrong with. */
        private const val MAX_DICT_ITEMS = 4_096

        /**
         * How long to let the first burst of globals arrive.
         *
         * The registry sends one event per object already on the graph as soon
         * as it is listening, so the list is complete a moment after connect
         * and not before. A consumer that asked for devices immediately would
         * otherwise get an empty list on a machine that has several, which is
         * the same lie as a backend that cannot enumerate.
         */
        private const val SETTLE_MILLIS = 150L

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
                    PipeWireRegistry(loop, context, core, registry).apply { installListener() }
                }
                // Then let the burst land, outside the lock, so the first
                // devices() call sees the graph rather than the start of it.
                Thread.sleep(SETTLE_MILLIS)
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
         * A proxy pointer is a `spa_interface`: its callbacks field points at
         * the interface's methods, and `get_registry` is one of them. Every
         * offset comes from `tools/pipewire-oracle.c`, for the reason the
         * WASAPI slot indices do, and the failure when one is wrong is the
         * same: a call through a function that is not the one meant.
         */
        private fun getRegistry(lib: PipeWireLibrary, core: MemorySegment): MemorySegment {
            // A sanity check the walk can actually make: offset zero of a
            // spa_interface is the name of the interface it is. A pointer that
            // is not one answers with something else or with nothing, and
            // finding that out here beats calling through a table that is not
            // a table.
            val declared = core.reinterpret(SpaAbi.INTERFACE_CB_FUNCS)
                .get(ValueLayout.ADDRESS, 0L).readCString()
            check(declared == SpaAbi.INTERFACE_CORE) {
                "the core does not declare itself a core: $declared"
            }
            val methods = core.reinterpret(SpaAbi.INTERFACE_CB_FUNCS + Long.SIZE_BYTES)
                .get(ValueLayout.ADDRESS, SpaAbi.INTERFACE_CB_FUNCS)
            check(methods.address() != 0L) { "the core carries no method table" }
            val getRegistry = methods.reinterpret(SpaAbi.CORE_METHOD_GET_REGISTRY + Long.SIZE_BYTES)
                .get(ValueLayout.ADDRESS, SpaAbi.CORE_METHOD_GET_REGISTRY)
            check(getRegistry.address() != 0L) { "the core's method table has no get_registry" }
            val call = Linker.nativeLinker().downcallHandle(
                getRegistry,
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ),
            )
            // The first argument is the interface's own callback data rather
            // than the proxy, which is what the macro passes and what a walk by
            // hand is most likely to get wrong.
            val data = core.reinterpret(SpaAbi.INTERFACE_CB_FUNCS + 2 * Long.SIZE_BYTES)
                .get(ValueLayout.ADDRESS, SpaAbi.INTERFACE_CB_FUNCS + Long.SIZE_BYTES)
            // The caller holds the loop lock.
            val registry = call.invokeExact(data, SpaAbi.VERSION_REGISTRY, 0L) as MemorySegment
            check(registry.address() != 0L) { "get_registry answered null" }
            return registry
        }
    }
}
