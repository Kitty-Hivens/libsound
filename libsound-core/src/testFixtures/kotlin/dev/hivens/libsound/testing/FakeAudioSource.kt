package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An [AudioSource] with the shape of a real microphone and none of its
 * unpredictability. The mirror of [FakeAudioSink], and deliberately the same
 * shape: the test that holds a sink mid-write and the one that holds a source
 * mid-read should look alike.
 *
 * The device produces nothing on its own. [produce] is the microphone, under
 * the test's control, so a read parks exactly where it would park against
 * hardware that has not filled its buffer yet, and the parking is observable
 * rather than inferred from a timing window. A fake that answered every read
 * instantly would let every pacing bug through, because nothing would ever
 * wait.
 */
public class FakeAudioSource(
    /** How much the device holds before what it captures starts being dropped. */
    public val bufferFrames: Int = 4_800,
    override val capabilities: Capabilities = Capabilities.of(
        Capability.CAPTURE,
        Capability.STREAM_VOLUME,
        Capability.STREAM_IDENTITY,
        Capability.DEVICE_POSITION,
    ),
) : AudioSource {

    private val lock = ReentrantLock()

    /** Signalled when audio arrives, the device is flushed, or it is closed. */
    private val dataAvailable = lock.newCondition()

    private var openFormat: AudioFormat? = null
    private var running = false
    private var closed = false

    /** Captured but unread, oldest first. */
    private var buffered = ByteArray(0)

    private var capturedFrames = 0L
    private var overruns = 0L
    private var volumeValue = 1f

    /** Reads attempted while the device was stopped: a deadlock waiting to happen. */
    public var readsWhileStopped: Int = 0
        private set

    /** How many times [open] has been called. */
    public var opens: Int = 0
        private set

    public var flushes: Int = 0
        private set

    /** Bytes captured and not yet read. */
    public fun bufferedFrames(): Long = lock.withLock {
        openFormat?.framesIn(buffered.size.toLong()) ?: 0L
    }

    override val format: AudioFormat? get() = lock.withLock { openFormat }

    override val isOpen: Boolean get() = lock.withLock { openFormat != null && !closed }

    /**
     * Capture [frames] frames of silence. The device's own progress, under the
     * test's control: this is what unparks a read and what moves
     * [framePosition]. More than the buffer holds is dropped and counted, the
     * way a real device drops what a slow consumer did not collect.
     */
    public fun produce(frames: Long) {
        val format = lock.withLock { openFormat } ?: return
        produce(ByteArray((frames * format.bytesPerFrame).toInt()))
    }

    /** As [produce], with content a test can assert on. */
    public fun produce(data: ByteArray) {
        lock.withLock {
            // A stopped device captures nothing. That is what freezes the
            // position, and why a read issued while stopped can never complete
            // on its own.
            if (!running || closed) return
            val format = openFormat ?: return
            val capacity = bufferFrames * format.bytesPerFrame
            val room = capacity - buffered.size
            val accepted = minOf(room, data.size) - (minOf(room, data.size) % format.bytesPerFrame)
            if (accepted < data.size) {
                overruns += format.framesIn((data.size - accepted).toLong())
            }
            if (accepted <= 0) return
            buffered += data.copyOfRange(0, accepted)
            capturedFrames += format.framesIn(accepted.toLong())
            dataAvailable.signalAll()
        }
    }

    override fun open(format: AudioFormat) {
        lock.withLock {
            if (closed) throw AudioException("source is closed")
            openFormat = format
            buffered = ByteArray(0)
            capturedFrames = 0
            overruns = 0
            opens++
            // Started, not merely prepared: a caller wanting the device open and
            // silent stops next.
            running = true
            dataAvailable.signalAll()
        }
    }

    override fun read(dst: ByteArray, offset: Int, length: Int) {
        val format = lock.withLock {
            openFormat ?: throw AudioException("read before open")
        }
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) {
            "range $offset..${offset + length} outside array of ${dst.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        lock.withLock {
            if (!running) readsWhileStopped++
            var filled = 0
            while (filled < length) {
                if (closed) throw AudioException("source closed while reading")
                if (buffered.isEmpty()) {
                    // Parked exactly where a real device parks. Nothing here
                    // fills the buffer on its own; the test drives it, or
                    // close() breaks it.
                    dataAvailable.await()
                    continue
                }
                val chunk = minOf(buffered.size, length - filled)
                System.arraycopy(buffered, 0, dst, offset + filled, chunk)
                buffered = buffered.copyOfRange(chunk, buffered.size)
                filled += chunk
            }
        }
    }

    override fun start() {
        lock.withLock {
            running = true
            dataAvailable.signalAll()
        }
    }

    override fun stop() {
        lock.withLock { running = false }
    }

    override fun flush() {
        lock.withLock {
            buffered = ByteArray(0)
            flushes++
            dataAvailable.signalAll()
        }
    }

    /**
     * Frames captured since [open]. Frozen while stopped, because [produce] is
     * the only thing that moves it and a stopped device produces nothing.
     */
    override fun framePosition(): Long = lock.withLock { capturedFrames }

    override fun latencyNanos(): Long = lock.withLock {
        val format = openFormat ?: return@withLock 0L
        format.nanosFor(format.framesIn(buffered.size.toLong()))
    }

    override fun overrunFrames(): Long = lock.withLock { overruns }

    override fun setVolume(volume: Float) {
        lock.withLock { volumeValue = volume.coerceIn(0f, 1f) }
    }

    override fun volume(): Float = lock.withLock { volumeValue }

    override fun close() {
        lock.withLock {
            closed = true
            openFormat = null
            running = false
            // The point of the rule: whoever is parked in read() is freed here,
            // because they cannot free themselves.
            dataAvailable.signalAll()
        }
    }
}
