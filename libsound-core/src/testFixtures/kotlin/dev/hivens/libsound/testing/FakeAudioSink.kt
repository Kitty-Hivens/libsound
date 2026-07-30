package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An [AudioSink] with the shape of real hardware and none of its unpredictability.
 *
 * The buffer is bounded and the playhead only moves when [consume] says so, so
 * a test can hold the sink genuinely mid-stream: a write parks when the buffer
 * fills, exactly as it would against a device, and the parking is observable
 * rather than inferred from a timing window. A fake that accepted everything
 * instantly would let every pacing bug pass, because nothing would ever wait.
 *
 * Every consumer's tests run against this -- including, downstream, the skinema
 * adapter's -- which is why it ships as a fixture instead of living in this
 * module's test sources.
 */
public class FakeAudioSink(
    /** How much the device holds before a write has to wait. */
    public val bufferFrames: Int = 4_800,
    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_VOLUME,
        Capability.STREAM_IDENTITY,
        Capability.DEVICE_POSITION,
    ),
) : AudioSink {

    private val lock = ReentrantLock()

    /** Signalled when the device drains, is closed, or starts running again. */
    private val roomAvailable = lock.newCondition()

    private var openFormat: AudioFormat? = null
    private var running = false
    private var closed = false

    private var bufferedFrames = 0L
    private var playedFrames = 0L
    private var volumeValue = 1f

    private val captured = ByteArrayOutputStream()

    /** Writes attempted while the device was stopped -- a deadlock waiting to happen. */
    public var writesWhileStopped: Int = 0
        private set

    /** How many times [open] has been called; a track switch reopens. */
    public var opens: Int = 0
        private set

    public var flushes: Int = 0
        private set

    /** Everything written since the last [open]. */
    public fun capturedBytes(): ByteArray = lock.withLock { captured.toByteArray() }

    /** Frames sitting in the device's buffer, written but not yet played. */
    public fun bufferedFrames(): Long = lock.withLock { bufferedFrames }

    override val format: AudioFormat? get() = lock.withLock { openFormat }

    override val isOpen: Boolean get() = lock.withLock { openFormat != null && !closed }

    /**
     * Play [frames] frames. The device's own progress, under the test's control:
     * this is what unparks a write waiting for room and what moves
     * [framePosition]. Playing more than is buffered plays what there is.
     */
    public fun consume(frames: Long) {
        lock.withLock {
            // A stopped device consumes nothing. That is what freezes the
            // position, and it is also why a write that fills the buffer while
            // stopped can never complete on its own.
            if (!running) return
            val actual = minOf(frames, bufferedFrames)
            bufferedFrames -= actual
            playedFrames += actual
            if (actual > 0) roomAvailable.signalAll()
        }
    }

    /** Play everything buffered. */
    public fun consumeAll() {
        lock.withLock { consume(bufferedFrames) }
    }

    override fun open(format: AudioFormat) {
        lock.withLock {
            if (closed) throw AudioException("sink is closed")
            openFormat = format
            // A reopen drops the previous stream and its tail, and the position
            // restarts -- the two rules a re-anchoring clock depends on.
            bufferedFrames = 0
            playedFrames = 0
            captured.reset()
            opens++
            // Started, not merely prepared: a caller wanting silence stops next.
            running = true
            roomAvailable.signalAll()
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = lock.withLock {
            openFormat ?: throw AudioException("write before open")
        }
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of ${data.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        val frames = format.framesIn(length.toLong())
        lock.withLock {
            if (!running) writesWhileStopped++
            var remaining = frames
            var cursor = offset
            while (remaining > 0) {
                if (closed) throw AudioException("sink closed while writing")
                val room = bufferFrames - bufferedFrames
                if (room <= 0) {
                    // Parked exactly where a real device parks. Nothing here
                    // drains on its own; the test drives it, or close() breaks it.
                    roomAvailable.await()
                    continue
                }
                val chunk = minOf(room, remaining)
                val bytes = (chunk * format.bytesPerFrame).toInt()
                captured.write(data, cursor, bytes)
                cursor += bytes
                bufferedFrames += chunk
                remaining -= chunk
            }
        }
    }

    override fun start() {
        lock.withLock {
            running = true
            roomAvailable.signalAll()
        }
    }

    override fun stop() {
        lock.withLock { running = false }
    }

    override fun flush() {
        lock.withLock {
            bufferedFrames = 0
            flushes++
            roomAvailable.signalAll()
        }
    }

    /**
     * Frames played since [open]. Frozen while stopped -- [consume] is the only
     * thing that moves it, and a stopped device is one nothing consumes from.
     */
    override fun framePosition(): Long = lock.withLock { playedFrames }

    override fun latencyNanos(): Long = lock.withLock {
        openFormat?.nanosFor(bufferedFrames) ?: 0L
    }

    override fun setVolume(volume: Float) {
        lock.withLock { volumeValue = volume.coerceIn(0f, 1f) }
    }

    override fun volume(): Float = lock.withLock { volumeValue }

    override fun close() {
        lock.withLock {
            closed = true
            openFormat = null
            running = false
            // The point of the rule: whoever is parked in write() is freed here,
            // because they cannot free themselves.
            roomAvailable.signalAll()
        }
    }
}
