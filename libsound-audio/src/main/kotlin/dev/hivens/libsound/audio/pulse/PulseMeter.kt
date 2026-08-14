package dev.hivens.libsound.audio.pulse

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One stream's level, as a recording stream on the sink's monitor source.
 *
 * There is no property to read. A level is the audio itself, so the only way to
 * know it is to listen: the server exposes every sink's output back as a
 * monitor source, and `pa_stream_set_monitor_stream` narrows that to a single
 * sink input rather than everything the sink is playing.
 *
 * ## What makes it cheap
 *
 * `PA_STREAM_PEAK_DETECT` changes what the server sends. Instead of the audio it
 * sends one sample per fragment, holding the loudest value in it -- so a meter
 * at [PulseAbi.METER_RATE] windows a second costs a hundred floats a second
 * rather than a copy of the stream. Without that flag this would be a recording
 * of somebody else's audio, which is a different thing to build and a different
 * thing to ask for.
 *
 * A monitor is created per watched stream and torn down when the watch is
 * cancelled, which is why the interface hands back a cancel rather than a
 * property: a mixer drawing twenty rows should listen to the ones on screen.
 */
internal class PulseMeter private constructor(
    private val pulse: PulseContext,
    private val stream: MemorySegment,
    private val onPeak: (Float) -> Unit,
    private val onFailure: (String) -> Unit,
) {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    private val closed = AtomicBoolean(false)

    private val lib = pulse.lib

    private lateinit var readStub: MemorySegment

    /**
     * Called on the mainloop thread with its lock held.
     *
     * Every fragment must be dropped after it is peeked, whether or not it held
     * anything: a hole in the stream arrives as a null pointer with a non-zero
     * length, and leaving it undropped stalls the stream permanently.
     */
    fun onRead(unusedStream: MemorySegment, unusedBytes: Long, unusedUserData: MemorySegment) {
        runCatching {
            Arena.ofConfined().use { call ->
                val data = call.allocate(ValueLayout.ADDRESS)
                val length = call.allocate(ValueLayout.JAVA_LONG)
                while (true) {
                    val rc = lib.handle("pa_stream_peek").invokeExact(stream, data, length) as Int
                    if (rc != 0) return@runCatching
                    val bytes = length.get(ValueLayout.JAVA_LONG, 0)
                    if (bytes == 0L) return@runCatching

                    val pointer = data.get(ValueLayout.ADDRESS, 0)
                    if (pointer.address() != 0L && bytes >= Float.SIZE_BYTES) {
                        // The last float in the fragment is the most recent
                        // window; earlier ones are already stale by the time a
                        // meter would draw them.
                        val peak = pointer.reinterpret(bytes)
                            .get(ValueLayout.JAVA_FLOAT, bytes - Float.SIZE_BYTES)
                        onPeak(peak.coerceIn(0f, 1f))
                    }
                    // Dropped even when the pointer was null: that is a hole,
                    // and an undropped hole stops the stream for good.
                    lib.handle("pa_stream_drop").invokeExact(stream) as Int
                }
            }
        }.onFailure { onFailure(it.message ?: "meter callback threw") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            pulse.locked {
                lib.handle("pa_stream_set_read_callback")
                    .invokeExact(stream, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_disconnect").invokeExact(stream) as Int
                lib.handle("pa_stream_unref").invokeExact(stream) as Unit
            }
        }.onFailure { log.debug("meter teardown threw: {}", it.message) }
    }

    private fun installStub() {
        readStub = Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findVirtual(
                PulseMeter::class.java, "onRead",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java,
                    Long::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            lib.arena,
        )
        pulse.locked {
            lib.handle("pa_stream_set_read_callback")
                .invokeExact(stream, readStub, MemorySegment.NULL) as Unit
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mixer")

        /**
         * Attach a meter to [sinkInputIndex] on [monitorSource], or null when the
         * server refuses.
         *
         * The caller holds the mainloop lock for none of this; every step takes
         * it itself.
         */
        fun openOrNull(
            pulse: PulseContext,
            monitorSource: String,
            sinkInputIndex: Int,
            onPeak: (Float) -> Unit,
            onFailure: (String) -> Unit,
        ): PulseMeter? {
            val lib = pulse.lib
            return runCatching {
                val stream = Arena.ofConfined().use { setup ->
                    val spec = setup.allocate(PulseAbi.SAMPLE_SPEC_SIZE, 4)
                    // One float per window, mono: with PEAK_DETECT the value is
                    // the loudest sample of the window rather than audio, so a
                    // channel count above one would buy nothing.
                    spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_FORMAT, PulseAbi.SAMPLE_FLOAT32LE)
                    spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_RATE, PulseAbi.METER_RATE)
                    spec.set(ValueLayout.JAVA_BYTE, PulseAbi.SAMPLE_SPEC_CHANNELS, 1)

                    pulse.locked {
                        val fresh = lib.handle("pa_stream_new_with_proplist").invokeExact(
                            pulse.context, setup.allocateUtf8("libsound meter"), spec,
                            MemorySegment.NULL, MemorySegment.NULL,
                        ) as MemorySegment
                        if (fresh.address() == 0L) error("pa_stream_new: ${pulse.lastError()}")

                        // Before connecting, or the stream is already listening
                        // to the whole sink and the narrowing arrives too late.
                        val aimed = lib.handle("pa_stream_set_monitor_stream")
                            .invokeExact(fresh, sinkInputIndex) as Int
                        if (aimed < 0) {
                            lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                            error("pa_stream_set_monitor_stream: ${pulse.lastError()}")
                        }

                        // One float per fragment, and everything else left to
                        // the server. Without this the server picks a default
                        // fragment size, which at 25 windows a second takes
                        // minutes to fill -- measured as exactly one callback
                        // and then silence, which reads like a broken meter
                        // rather than a slow one.
                        val attr = setup.allocate(PulseAbi.BUFFER_ATTR_SIZE, 4)
                        attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MAXLENGTH, PulseAbi.ATTR_DEFAULT)
                        attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_TLENGTH, PulseAbi.ATTR_DEFAULT)
                        attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_PREBUF, PulseAbi.ATTR_DEFAULT)
                        attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MINREQ, PulseAbi.ATTR_DEFAULT)
                        attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_FRAGSIZE, Float.SIZE_BYTES)

                        val flags = PulseAbi.STREAM_PEAK_DETECT or
                            PulseAbi.STREAM_ADJUST_LATENCY or
                            PulseAbi.STREAM_DONT_MOVE
                        val rc = lib.handle("pa_stream_connect_record").invokeExact(
                            fresh, setup.allocateUtf8(monitorSource), attr, flags,
                        ) as Int
                        if (rc < 0) {
                            lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                            error("pa_stream_connect_record: ${pulse.lastError()}")
                        }
                        fresh
                    }
                }
                PulseMeter(pulse, stream, onPeak, onFailure).apply { installStub() }
            }.getOrElse {
                log.debug("no meter for sink input {}: {}", sinkInputIndex, it.message)
                null
            }
        }
    }
}
