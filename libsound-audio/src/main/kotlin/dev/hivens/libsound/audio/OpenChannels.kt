package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The channels a backend has handed out, and the flag that says whether it will
 * hand out any more.
 *
 * [dev.hivens.libsound.AudioBackend.close] promises to release every sink made
 * from the backend, and each backend kept that promise with a list and a flag of
 * its own. Five copies of one invariant drifted into four behaviours: two
 * backends refused after a close, one refused with a different exception, and
 * two handed a channel back onto a connection that had already been torn down.
 *
 * Worse than the inconsistency, the ones that did check checked and then added,
 * which is two steps with a close able to run whole between them. The channel
 * then landed on a list the teardown had already walked and cleared, so nothing
 * ever closed it and a live stream outlived the connection it was opened on.
 *
 * So the flag and the list live here instead of once per backend. The check and
 * the add happen under one monitor, [closeAll] takes the same one, and a channel
 * is therefore either on the list the teardown walks or was never handed out.
 */
internal class OpenChannels {

    /** Guards the window between deciding the backend is open and acting on it. */
    private val lifecycle = Any()

    private val closed = AtomicBoolean(false)

    /**
     * Sinks and sources in one list, because closing all of it is the only thing
     * anybody does with it and the two directions close independently of each
     * other.
     */
    private val channels = CopyOnWriteArrayList<AutoCloseable>()

    /** True once a teardown has been claimed, which is what every query asks. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Take [channel] onto the list, or close it again and refuse.
     *
     * @throws AudioException when the backend has been closed. Closed here
     *   rather than handed back dead, because a sink that accepts every call and
     *   plays nothing is worse than one that says so.
     */
    fun <T : AutoCloseable> register(channel: T): T {
        val accepted = synchronized(lifecycle) {
            if (closed.get()) false else channels.add(channel)
        }
        if (!accepted) {
            runCatching { channel.close() }
            throw AudioException("backend is closed")
        }
        return channel
    }

    /**
     * Claim the teardown, so whoever gets it does it once. False for everybody
     * after the first.
     *
     * Apart from [closeAll] because a backend has work of its own to do between
     * the two: the libpulse one takes the samples it uploaded off the server
     * while the connection that can still reach them is up.
     */
    fun claim(): Boolean = closed.compareAndSet(false, true)

    /** Close everything handed out. For a caller that has claimed the teardown. */
    fun closeAll() {
        // Let a register that is already past its own check land on the list, so
        // what follows walks a list nothing is still adding to.
        synchronized(lifecycle) { }
        channels.forEach { runCatching { it.close() } }
        channels.clear()
    }
}
