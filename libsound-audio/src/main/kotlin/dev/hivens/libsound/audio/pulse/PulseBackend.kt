package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.SinkConfig
import org.slf4j.LoggerFactory
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The PulseAudio backend, which is also the PipeWire backend: `pipewire-pulse`
 * speaks the same protocol, so one binding covers both servers and the choice
 * between them stops being this library's problem.
 *
 * Everything the fallback cannot do lives here -- a named stream, volume the
 * desktop's mixer shows and follows, a device list worth offering, and events
 * when the default moves.
 */
internal class PulseBackend private constructor(
    private val pulse: PulseContext,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.Pulse")

    private val lib = pulse.lib

    override val name: String = "pulse"

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_VOLUME,
        Capability.STREAM_IDENTITY,
        Capability.DEVICE_ENUMERATION,
        Capability.DEVICE_SELECTION,
        Capability.DEVICE_EVENTS,
        Capability.DEVICE_POSITION,
    )

    /**
     * Device-change handlers run here, never on the mainloop thread.
     *
     * libpulse delivers the subscription callback on its own thread with the
     * mainloop lock held, and the natural response to the event -- re-reading
     * the device list -- calls pa_threaded_mainloop_wait. On the mainloop
     * thread that parks the loop waiting for a signal only that loop could
     * deliver, and audio, introspection and teardown all stop for good.
     */
    private val eventDispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-pulse-events").apply { isDaemon = true }
    }

    private val sinks = CopyOnWriteArrayList<PulseSink>()
    private val deviceListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * One introspection round trip at a time.
     *
     * Holding the mainloop lock is not enough on its own:
     * `pa_threaded_mainloop_wait` releases it while it waits, so two threads
     * asking for the device list would each collect into the other's buffer.
     * A settings screen refreshing while the subscription dispatcher answers a
     * default-sink change is exactly that, and is the ordinary case rather than
     * a contrived one.
     *
     * Always taken before the mainloop lock, never while holding it.
     */
    private val roundTrip = ReentrantLock()

    private val closed = AtomicBoolean(false)

    // Written on the mainloop thread by the upcalls, read by the thread holding
    // roundTrip that issued the call. The completion flags are volatile and are
    // written last, so a reader that sees one set also sees everything the
    // callback wrote before it. The mainloop's own mutex cannot supply that
    // edge: it is native, and the Java memory model cannot see it.
    private val collected = mutableListOf<AudioDevice>()

    @Volatile
    private var collectComplete = false

    @Volatile
    private var defaultSinkName: String? = null

    @Volatile
    private var serverInfoComplete = false

    private lateinit var sinkInfoStub: MemorySegment
    private lateinit var serverInfoStub: MemorySegment
    private lateinit var subscribeStub: MemorySegment

    override fun createSink(config: SinkConfig): AudioSink {
        val sink = PulseSink(pulse, config, SINK_CAPABILITIES)
        sinks.add(sink)
        return sink
    }

    override fun devices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        return roundTrip.withLock {
            val default = queryDefaultSinkName()
            pulse.locked {
                collected.clear()
                collectComplete = false
                val op = lib.handle("pa_context_get_sink_info_list")
                    .invokeExact(pulse.context, sinkInfoStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked emptyList()
                pulse.releaseOperation(op)
                if (!awaitFlag { collectComplete }) return@locked emptyList()
                collected.map { it.copy(isDefault = it.id.value == default) }
            }
        }
    }

    /** The list already carries the answer, so asking the server twice buys nothing. */
    override fun defaultDevice(): AudioDevice? = devices().firstOrNull { it.isDefault }

    override fun onDevicesChanged(handler: () -> Unit): () -> Unit {
        deviceListeners.add(handler)
        return { deviceListeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        deviceListeners.clear()
        // Drained, not killed. A handler runs on this thread and its natural
        // first move is to re-read the device list, which parks on the mainloop
        // -- a wait that shutdownNow cannot interrupt because it is native.
        // Tearing the context down underneath it would free what it is reading.
        // Its work ends quickly regardless: devices() answers empty once closed.
        eventDispatch.shutdown()
        runCatching { eventDispatch.awaitTermination(2, TimeUnit.SECONDS) }
        pulse.close()
    }

    // -- upcalls, all on the mainloop thread with its lock held --------------

    // Public rather than internal for the same reason as PulseContext's
    // callbacks: Kotlin mangles an internal name and findVirtual would miss it.
    fun onSinkInfo(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                // Positive is end of list, negative is an error; either way
                // nothing more is coming and the caller must stop waiting.
                if (eol < 0) log.debug("sink info list ended with {}", eol)
                collectComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(SINK_INFO_HEAD)
            val sinkName = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_NAME).readCString() ?: return@runCatching
            val description = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_DESCRIPTION).readCString()
            collected.add(
                AudioDevice(
                    id = DeviceId(sinkName),
                    name = description ?: sinkName,
                ),
            )
        }.onFailure { log.warn("sink info callback threw: {}", it.message) }
    }

    fun onServerInfo(unusedContext: MemorySegment, info: MemorySegment, unusedUserData: MemorySegment) {
        runCatching {
            defaultSinkName = if (info.address() == 0L) {
                null
            } else {
                info.reinterpret(SERVER_INFO_HEAD)
                    .get(ValueLayout.ADDRESS, PulseAbi.SERVER_INFO_DEFAULT_SINK_NAME)
                    .readCString()
            }
            serverInfoComplete = true
            pulse.signal()
        }.onFailure { log.warn("server info callback threw: {}", it.message) }
    }

    fun onSubscribe(
        unusedContext: MemorySegment,
        unusedEvent: Int,
        unusedIndex: Int,
        unusedUserData: MemorySegment,
    ) {
        // Deliberately coarse: the event says which sink changed, but every
        // consumer of this signal re-reads the whole list anyway, and a
        // per-device diff would be state to keep correct for no gain.
        val handlers = deviceListeners.toList()
        if (handlers.isEmpty()) return
        runCatching {
            eventDispatch.execute {
                handlers.forEach { handler ->
                    runCatching { handler() }.onFailure { log.warn("device listener threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("device event dropped, dispatcher is shut down") }
    }

    // -- internals -----------------------------------------------------------

    /** Caller holds [roundTrip]. */
    private fun queryDefaultSinkName(): String? = pulse.locked {
        defaultSinkName = null
        serverInfoComplete = false
        val op = lib.handle("pa_context_get_server_info")
            .invokeExact(pulse.context, serverInfoStub, MemorySegment.NULL) as MemorySegment
        if (op.address() == 0L) return@locked null
        pulse.releaseOperation(op)
        if (!awaitFlag { serverInfoComplete }) null else defaultSinkName
    }

    /**
     * Park until [done], the connection drops, or the deadline passes.
     *
     * `pa_threaded_mainloop_wait` has no timeout of its own, so a server that
     * dies mid-operation would leave this parked for the life of the process.
     * The context state is checked on every wakeup for exactly that: an
     * introspection call is not worth hanging a consumer's settings screen.
     */
    private inline fun awaitFlag(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + INTROSPECT_TIMEOUT_NANOS
        while (!done()) {
            val state = lib.handle("pa_context_get_state").invokeExact(pulse.context) as Int
            if (state != PulseAbi.CONTEXT_READY) {
                log.debug("introspection abandoned: context state {}", state)
                return false
            }
            if (System.nanoTime() > deadline) {
                log.warn("introspection timed out after {} ms", INTROSPECT_TIMEOUT_NANOS / 1_000_000)
                return false
            }
            pulse.await()
        }
        return true
    }

    private fun installStubs() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT

        sinkInfoStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onSinkInfo",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                    Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        serverInfoStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onServerInfo",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, addr),
            lib.arena,
        )
        subscribeStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onSubscribe",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, i32, addr),
            lib.arena,
        )
    }

    private fun subscribe() {
        pulse.locked {
            lib.handle("pa_context_set_subscribe_callback")
                .invokeExact(pulse.context, subscribeStub, MemorySegment.NULL) as Unit
            val mask = PulseAbi.SUBSCRIPTION_MASK_SINK or PulseAbi.SUBSCRIPTION_MASK_SERVER
            val op = lib.handle("pa_context_subscribe")
                .invokeExact(pulse.context, mask, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Pulse")

        /** Only the head of pa_sink_info is read; the rest of the struct is not our business. */
        private const val SINK_INFO_HEAD = 32L
        private const val SERVER_INFO_HEAD = 64L

        private const val INTROSPECT_TIMEOUT_NANOS = 2_000_000_000L

        /**
         * What a *sink* can do, which is not what the backend can do. A sink
         * cannot enumerate devices, cannot subscribe to device events, and has
         * no way to change the device it was created against -- handing it the
         * backend's set claimed all three.
         */
        private val SINK_CAPABILITIES = Capabilities.of(
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
            Capability.DEVICE_POSITION,
        )

        /** Connect and return the backend, or null when there is no sound server. */
        fun connectOrNull(applicationName: String): AudioBackend? {
            val context = PulseContext.connectOrNull(applicationName) ?: return null
            return runCatching {
                PulseBackend(context).apply {
                    installStubs()
                    subscribe()
                }
            }.getOrElse {
                log.debug("PulseAudio backend setup failed: {}", it.message)
                context.close()
                null
            }
        }
    }
}

/** Read a `const char *` out of a pointer-valued field. */
internal fun MemorySegment.readCString(): String? =
    if (address() == 0L) null else reinterpret(Long.MAX_VALUE).getString(0)
