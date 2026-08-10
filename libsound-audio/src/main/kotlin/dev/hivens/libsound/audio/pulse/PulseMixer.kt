package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioMixer
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everyone else's streams, over the same libpulse the sink uses.
 *
 * `pa_context_get_sink_input_info_list` beside the `..._sink_info_list` the
 * output backend already binds: same callback shape, same introspection wait,
 * same subscription. Which is why this is not a separate artifact -- it shares
 * the native dependency, and a module boundary drawn by subject rather than by
 * dependency would buy a consumer nothing.
 *
 * ## What it puts back
 *
 * A sound server remembers per-application volume, so this is the one surface in
 * the library that writes state outliving its process. Every change is recorded
 * against the value it replaced, and [close] restores whatever is still
 * outstanding. That covers an orderly exit and not a crash, which is why a
 * consumer whose ducking is temporary should prefer the media role where the
 * desktop honours it: a role vanishes with the stream that asked for it, and a
 * volume does not.
 */
internal class PulseMixer private constructor(
    private val pulse: PulseContext,
    private val ourStreamIndices: Set<Int>,
) : AudioMixer {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    private val lib = pulse.lib

    private val closed = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /** Original volume per stream we changed, so close() can put it back. */
    private val originals = ConcurrentHashMap<Int, Float>()

    /** Handlers never run on the mainloop thread; see PulseBackend for why. */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-mixer-events").apply { isDaemon = true }
    }

    // Touched on the mainloop thread from the upcalls, or under its lock from
    // the collecting calls below.
    private val collected = mutableListOf<AudioStream>()
    private var collectComplete = false
    private val sinkNames = ConcurrentHashMap<Int, String>()

    private lateinit var sinkInputStub: MemorySegment
    private lateinit var sinkStub: MemorySegment
    private lateinit var subscribeStub: MemorySegment

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_ENUMERATION,
        Capability.STREAM_CONTROL,
        Capability.STREAM_ROUTING,
    )

    override val isOpen: Boolean get() = !closed.get()

    override fun streams(): List<AudioStream> {
        if (closed.get()) return emptyList()
        return pulse.locked {
            collected.clear()
            collectComplete = false
            val op = lib.handle("pa_context_get_sink_input_info_list")
                .invokeExact(pulse.context, sinkInputStub, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
            if (!awaitFlag { collectComplete }) return@locked emptyList()
            collected.toList()
        }
    }

    override fun setVolume(id: StreamId, volume: Float): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        val clamped = volume.coerceIn(0f, 1f)
        // Remember what it was before the first change, not before each one:
        // restoring has to reach the value the user had, not the one we set a
        // moment ago.
        originals.computeIfAbsent(index) { currentVolume(index) ?: clamped }
        return pulse.locked {
            Arena.ofConfined().use { call ->
                val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
                val level = lib.handle("pa_sw_volume_from_linear").invokeExact(clamped.toDouble()) as Int
                // Channel count from the stream itself would be better, but a
                // cvolume set across the maximum applies to however many it has.
                lib.handle("pa_cvolume_set").invokeExact(cvolume, CHANNELS_MAX, level) as MemorySegment
                val op = lib.handle("pa_context_set_sink_input_volume").invokeExact(
                    pulse.context, index, cvolume, MemorySegment.NULL, MemorySegment.NULL,
                ) as MemorySegment
                val ok = op.address() != 0L
                pulse.releaseOperation(op)
                ok
            }
        }
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        return pulse.locked {
            val op = lib.handle("pa_context_set_sink_input_mute").invokeExact(
                pulse.context, index, if (muted) 1 else 0, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            val ok = op.address() != 0L
            pulse.releaseOperation(op)
            ok
        }
    }

    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        return pulse.locked {
            Arena.ofConfined().use { call ->
                val op = lib.handle("pa_context_move_sink_input_by_name").invokeExact(
                    pulse.context, index, call.allocateUtf8(device.value),
                    MemorySegment.NULL, MemorySegment.NULL,
                ) as MemorySegment
                val ok = op.address() != 0L
                pulse.releaseOperation(op)
                ok
            }
        }
    }

    override fun restoreAll() {
        val outstanding = originals.entries.toList()
        originals.clear()
        outstanding.forEach { (index, volume) ->
            runCatching { setVolumeWithoutRecording(index, volume) }
                .onFailure { log.debug("could not restore stream {}: {}", index, it.message) }
        }
    }

    override fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Before the connection goes: a restore needs it.
        runCatching { restoreOnClose() }
        listeners.clear()
        dispatch.shutdownNow()
        pulse.close()
    }

    private fun restoreOnClose() {
        val outstanding = originals.entries.toList()
        originals.clear()
        if (outstanding.isEmpty()) return
        log.info("restoring {} stream volume(s) this process changed", outstanding.size)
        outstanding.forEach { (index, volume) ->
            runCatching { setVolumeWithoutRecording(index, volume) }
        }
    }

    private fun setVolumeWithoutRecording(index: Int, volume: Float) {
        pulse.locked {
            Arena.ofConfined().use { call ->
                val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
                val level = lib.handle("pa_sw_volume_from_linear")
                    .invokeExact(volume.coerceIn(0f, 1f).toDouble()) as Int
                lib.handle("pa_cvolume_set").invokeExact(cvolume, CHANNELS_MAX, level) as MemorySegment
                val op = lib.handle("pa_context_set_sink_input_volume").invokeExact(
                    pulse.context, index, cvolume, MemorySegment.NULL, MemorySegment.NULL,
                ) as MemorySegment
                pulse.releaseOperation(op)
            }
        }
    }

    private fun currentVolume(index: Int): Float? =
        streams().firstOrNull { it.id.value == index.toString() }?.volume

    // -- upcalls, on the mainloop thread with its lock held ---------------------

    // Public rather than internal: Kotlin mangles an internal name and
    // findVirtual looks up what is written.

    fun onSinkInput(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                collectComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(PulseAbi.SINK_INPUT_HEAD)
            val index = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_INDEX)
            val proplist = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INPUT_PROPLIST)
            val sinkIndex = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_SINK)
            val muted = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_MUTE) != 0
            val volume = readVolume(head.asSlice(PulseAbi.SINK_INPUT_VOLUME, PulseAbi.CVOLUME_SIZE))

            collected.add(
                AudioStream(
                    id = StreamId(index.toString()),
                    applicationName = prop(proplist, PulseAbi.PROP_APPLICATION_NAME)
                        ?: prop(proplist, PulseAbi.PROP_APPLICATION_PROCESS_BINARY),
                    applicationId = prop(proplist, PulseAbi.PROP_APPLICATION_ID),
                    iconName = prop(proplist, PulseAbi.PROP_APPLICATION_ICON_NAME),
                    mediaRole = roleOf(prop(proplist, PulseAbi.PROP_MEDIA_ROLE)),
                    device = sinkNames[sinkIndex]?.let { DeviceId(it) },
                    volume = volume,
                    muted = muted,
                    isOurs = index in ourStreamIndices,
                ),
            )
        }.onFailure { log.warn("sink input callback threw: {}", it.message) }
    }

    fun onSink(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0 || info.address() == 0L) return@runCatching
            val head = info.reinterpret(SINK_HEAD)
            val index = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INFO_INDEX)
            val name = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_NAME).readCString()
            if (name != null) sinkNames[index] = name
        }.onFailure { log.warn("sink callback threw: {}", it.message) }
    }

    fun onSubscribe(unusedContext: MemorySegment, event: Int, index: Int, unusedUserData: MemorySegment) {
        // The event packs facility and kind into one int; reading either without
        // masking gives a number matching nothing.
        if ((event and PulseAbi.SUBSCRIPTION_EVENT_FACILITY_MASK) != PulseAbi.SUBSCRIPTION_EVENT_SINK_INPUT) return
        val kind = event and PulseAbi.SUBSCRIPTION_EVENT_TYPE_MASK
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            dispatch.execute {
                val streamEvent = when (kind) {
                    PulseAbi.SUBSCRIPTION_EVENT_REMOVE -> StreamEvent.Gone(StreamId(index.toString()))
                    else -> {
                        val stream = streams().firstOrNull { it.id.value == index.toString() } ?: return@execute
                        if (kind == PulseAbi.SUBSCRIPTION_EVENT_NEW) StreamEvent.Appeared(stream)
                        else StreamEvent.Changed(stream)
                    }
                }
                snapshot.forEach { listener ->
                    runCatching { listener(streamEvent) }
                        .onFailure { log.warn("stream listener threw: {}", it.message) }
                }
            }
        }
    }

    // -- internals --------------------------------------------------------------

    /** The loudest channel, which is what a mixer slider shows. */
    private fun readVolume(cvolume: MemorySegment): Float {
        val raw = lib.handle("pa_cvolume_max").invokeExact(cvolume) as Int
        return (lib.handle("pa_sw_volume_to_linear").invokeExact(raw) as Double)
            .toFloat().coerceIn(0f, 1f)
    }

    private fun prop(proplist: MemorySegment, key: String): String? {
        if (proplist.address() == 0L) return null
        return Arena.ofConfined().use { call ->
            (lib.handle("pa_proplist_gets").invokeExact(proplist, call.allocateUtf8(key)) as MemorySegment)
                .readCString()
        }
    }

    private fun roleOf(wireName: String?): MediaRole? =
        wireName?.lowercase()?.let { name -> MediaRole.entries.firstOrNull { it.wireName == name } }

    private inline fun awaitFlag(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + INTROSPECT_TIMEOUT_NANOS
        while (!done()) {
            val state = lib.handle("pa_context_get_state").invokeExact(pulse.context) as Int
            if (state != PulseAbi.CONTEXT_READY) return false
            if (System.nanoTime() > deadline) {
                log.warn("stream introspection timed out")
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
        val infoType = MethodType.methodType(
            Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
            Int::class.javaPrimitiveType, MemorySegment::class.java,
        )
        sinkInputStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSinkInput", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        sinkStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSink", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        subscribeStub = linker.upcallStub(
            lookup.findVirtual(
                PulseMixer::class.java, "onSubscribe",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, i32, addr), lib.arena,
        )
    }

    private fun primeSinkNames() {
        pulse.locked {
            val op = lib.handle("pa_context_get_sink_info_list")
                .invokeExact(pulse.context, sinkStub, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    private fun subscribe() {
        pulse.locked {
            lib.handle("pa_context_set_subscribe_callback")
                .invokeExact(pulse.context, subscribeStub, MemorySegment.NULL) as Unit
            val op = lib.handle("pa_context_subscribe").invokeExact(
                pulse.context, PulseAbi.SUBSCRIPTION_MASK_SINK_INPUT, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mixer")

        private const val CHANNELS_MAX = 2
        private const val SINK_HEAD = 32L
        private const val INTROSPECT_TIMEOUT_NANOS = 2_000_000_000L

        /**
         * Open a mixer, or null where there is no sound server.
         *
         * Its own connection, not one shared with an output backend: they would
         * work on one, but a mixer subscribing to every stream event on the
         * connection a sink is writing through puts introspection traffic on the
         * path that carries audio timing.
         */
        fun openOrNull(applicationName: String, ourStreamIndices: Set<Int> = emptySet()): AudioMixer? {
            val context = PulseContext.connectOrNull(applicationName) ?: return null
            return runCatching {
                PulseMixer(context, ourStreamIndices).apply {
                    installStubs()
                    primeSinkNames()
                    subscribe()
                }
            }.getOrElse {
                log.debug("mixer unavailable: {}", it.message)
                context.close()
                null
            }
        }
    }
}
