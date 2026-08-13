package dev.hivens.libsound.session.macos

import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
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
 * Our own media session on macOS, through `MPNowPlayingInfoCenter`.
 *
 * What puts a title in the Now Playing widget and Control Centre, and what makes
 * the media keys on a Mac keyboard reach this process.
 *
 * ## It works without a bundle, which was not obvious
 *
 * `MPNowPlayingInfoCenter` is documented for applications, and a JVM launched
 * from a shell has no bundle, no `Info.plist` and no activation. Measured before
 * any of this was written -- `tools/mpnowplaying-probe.m` on a CI runner -- and
 * the framework accepts it: the centre exists, the info is kept and reads back,
 * and the command centre hands out its commands. Whether the widget *shows* it
 * and whether a key press arrives still needs a person at a real desktop, which
 * no runner can be.
 *
 * ## Unlike the other two, this one only publishes
 *
 * Reading another application's session on macOS needs the private MediaRemote
 * framework, so [dev.hivens.libsound.SessionReader] has no backend here and says
 * so through its absence rather than through an empty list.
 */
internal class MpNowPlayingSession private constructor(
    private val objc: Objc,
    private val centre: MemorySegment,
    private val commands: MemorySegment,
) : MediaSession {

    private val log = LoggerFactory.getLogger("libsound.Session")

    private val closed = AtomicBoolean(false)

    private val handlers = CopyOnWriteArrayList<(SessionCommand) -> Unit>()

    /** One update at a time; a publish builds a dictionary and hands it over whole. */
    private val updating = ReentrantLock()

    /** Command handlers never run on the thread the framework calls us on. */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-nowplaying-events").apply { isDaemon = true }
    }

    /** What was last sent, so a repeat can be skipped -- see [publish]. */
    @Volatile
    private var lastPublished: SessionState? = null

    override val capabilities: Capabilities = Capabilities.of(Capability.SESSION_PUBLISH)

    override val isOpen: Boolean get() = !closed.get()

    /**
     * The whole dictionary goes each time, because that is the shape of the API:
     * `nowPlayingInfo` is replaced rather than amended. What is skipped is the
     * call itself when nothing changed, which is what keeps a position timer
     * from rebuilding the widget at its rate.
     */
    override fun publish(state: SessionState) {
        if (closed.get()) return
        updating.withLock {
            if (state == lastPublished) return@withLock
            runCatching {
                Arena.ofConfined().use { call ->
                    val info = objc.send(objc.cls(MediaPlayerAbi.CLASS_MUTABLE_DICTIONARY), MediaPlayerAbi.SEL_DICTIONARY)
                    state.metadata.title?.let { put(call, info, MediaPlayerAbi.KEY_TITLE, it) }
                    state.metadata.artists.firstOrNull()?.let { put(call, info, MediaPlayerAbi.KEY_ARTIST, it) }
                    state.metadata.album?.let { put(call, info, MediaPlayerAbi.KEY_ALBUM, it) }
                    state.metadata.durationMicros?.let {
                        putNumber(call, info, MediaPlayerAbi.KEY_DURATION, it / MICROS_PER_SECOND)
                    }
                    putNumber(call, info, MediaPlayerAbi.KEY_ELAPSED, state.positionMicros / MICROS_PER_SECOND)
                    // Zero while paused, or the widget keeps extrapolating the
                    // position from the moment it was told.
                    putNumber(
                        call, info, MediaPlayerAbi.KEY_RATE,
                        if (state.playback == PlaybackState.PLAYING) state.rate else 0.0,
                    )
                    objc.send(centre, MediaPlayerAbi.SEL_SET_NOW_PLAYING_INFO, info)
                    objc.sendLong(centre, MediaPlayerAbi.SEL_SET_PLAYBACK_STATE, playbackState(state.playback))
                }
                enableCommands(state)
                lastPublished = state
            }.onFailure { log.warn("could not publish the session state: {}", it.message) }
        }
    }

    /**
     * A no-op, and deliberately so.
     *
     * The Now Playing widget derives the position from the elapsed time and the
     * rate it was last given, so a seek is announced by publishing the new
     * elapsed time -- which the consumer does anyway. There is no separate call
     * to make, and inventing one would mean republishing the whole dictionary
     * twice for the same event.
     */
    override fun seeked(positionMicros: Long) = Unit

    override fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handlers.clear()
        lastPublished = null
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        updating.withLock {
            // Clearing first: a session left published is a player the desktop
            // still offers to drive after this process stopped answering.
            runCatching {
                objc.send(centre, MediaPlayerAbi.SEL_SET_NOW_PLAYING_INFO, MemorySegment.NULL)
                objc.sendLong(centre, MediaPlayerAbi.SEL_SET_PLAYBACK_STATE, MediaPlayerAbi.PLAYBACK_STATE_STOPPED)
            }
            objc.close()
        }
    }

    // -- publishing helpers ----------------------------------------------------

    private fun put(arena: Arena, info: MemorySegment, key: String, value: String) {
        objc.send(info, MediaPlayerAbi.SEL_SET_OBJECT_FOR_KEY, objc.nsString(arena, value), objc.nsString(arena, key))
    }

    private fun putNumber(arena: Arena, info: MemorySegment, key: String, value: Double) {
        val number = objc.sendDouble(
            objc.cls(MediaPlayerAbi.CLASS_NUMBER), MediaPlayerAbi.SEL_NUMBER_WITH_DOUBLE, value,
        )
        objc.send(info, MediaPlayerAbi.SEL_SET_OBJECT_FOR_KEY, number, objc.nsString(arena, key))
    }

    private fun playbackState(playback: PlaybackState): Long = when (playback) {
        PlaybackState.PLAYING -> MediaPlayerAbi.PLAYBACK_STATE_PLAYING
        PlaybackState.PAUSED -> MediaPlayerAbi.PLAYBACK_STATE_PAUSED
        PlaybackState.STOPPED -> MediaPlayerAbi.PLAYBACK_STATE_STOPPED
    }

    /** Which keys the system offers, from what the state says the player accepts. */
    private fun enableCommands(state: SessionState) {
        setEnabled(MediaPlayerAbi.SEL_PLAY_COMMAND, state.canPlay)
        setEnabled(MediaPlayerAbi.SEL_PAUSE_COMMAND, state.canPause)
        setEnabled(MediaPlayerAbi.SEL_NEXT_COMMAND, state.canGoNext)
        setEnabled(MediaPlayerAbi.SEL_PREVIOUS_COMMAND, state.canGoPrevious)
    }

    private fun setEnabled(commandSelector: String, enabled: Boolean) {
        val command = objc.send(commands, commandSelector)
        if (command.address() == 0L) return
        objc.sendLong(command, MediaPlayerAbi.SEL_SET_ENABLED, if (enabled) 1L else 0L)
    }

    // -- the block the framework invokes ---------------------------------------

    // Public rather than internal although nothing outside calls these: Kotlin
    // mangles an internal name and findVirtual looks up what is written.

    fun onPlay(unusedBlock: MemorySegment, unusedEvent: MemorySegment): Long = fire(SessionCommand.Play)

    fun onPause(unusedBlock: MemorySegment, unusedEvent: MemorySegment): Long = fire(SessionCommand.Pause)

    fun onStop(unusedBlock: MemorySegment, unusedEvent: MemorySegment): Long = fire(SessionCommand.Stop)

    fun onNext(unusedBlock: MemorySegment, unusedEvent: MemorySegment): Long = fire(SessionCommand.Next)

    fun onPrevious(unusedBlock: MemorySegment, unusedEvent: MemorySegment): Long = fire(SessionCommand.Previous)

    private fun fire(command: SessionCommand): Long {
        val snapshot = handlers.toList()
        if (snapshot.isEmpty()) return MediaPlayerAbi.HANDLER_STATUS_COMMAND_FAILED
        return runCatching {
            // Off the framework's thread before the consumer sees it: the natural
            // response is to publish new state, which is a call back into the
            // same framework from inside its own callback.
            dispatch.execute {
                snapshot.forEach { handler ->
                    runCatching { handler(command) }.onFailure { log.warn("command handler threw: {}", it.message) }
                }
            }
            MediaPlayerAbi.HANDLER_STATUS_SUCCESS
        }.getOrDefault(MediaPlayerAbi.HANDLER_STATUS_COMMAND_FAILED)
    }

    private fun installHandlers() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val type = MethodType.methodType(
            Long::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java,
        )
        val descriptor = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

        fun attach(commandSelector: String, method: String) {
            val command = objc.send(commands, commandSelector)
            if (command.address() == 0L) {
                log.debug("no {} on this system", commandSelector)
                return
            }
            val stub = linker.upcallStub(
                lookup.findVirtual(MpNowPlayingSession::class.java, method, type).bindTo(this),
                descriptor, objc.arena,
            )
            objc.send(command, MediaPlayerAbi.SEL_ADD_TARGET_WITH_HANDLER, objc.globalBlock(stub))
        }

        attach(MediaPlayerAbi.SEL_PLAY_COMMAND, "onPlay")
        attach(MediaPlayerAbi.SEL_PAUSE_COMMAND, "onPause")
        attach(MediaPlayerAbi.SEL_STOP_COMMAND, "onStop")
        attach(MediaPlayerAbi.SEL_NEXT_COMMAND, "onNext")
        attach(MediaPlayerAbi.SEL_PREVIOUS_COMMAND, "onPrevious")
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Session")

        private const val MICROS_PER_SECOND = 1_000_000.0

        /**
         * Open a session, or null where macOS will not give one.
         *
         * [config] is carried for symmetry with the other backends and is not
         * used: MediaPlayer takes the application's identity from the process
         * rather than from anything we can tell it, so passing a name here would
         * be a parameter with no effect.
         */
        fun openOrNull(config: SessionConfig): MediaSession? {
            val objc = Objc.loadOrNull() ?: return null
            return runCatching {
                val centreClass = objc.cls(MediaPlayerAbi.CLASS_NOW_PLAYING_INFO_CENTER)
                if (centreClass.address() == 0L) error("no MPNowPlayingInfoCenter on this system")
                val centre = objc.send(centreClass, MediaPlayerAbi.SEL_DEFAULT_CENTER)
                if (centre.address() == 0L) error("MPNowPlayingInfoCenter gave no default centre")

                val commandClass = objc.cls(MediaPlayerAbi.CLASS_REMOTE_COMMAND_CENTER)
                val commands = if (commandClass.address() == 0L) {
                    MemorySegment.NULL
                } else {
                    objc.send(commandClass, MediaPlayerAbi.SEL_SHARED_COMMAND_CENTER)
                }

                MpNowPlayingSession(objc, centre, commands).apply {
                    if (commands.address() != 0L) installHandlers()
                    else log.info("no remote command centre; the session will show but not be driven")
                }
            }.getOrElse {
                log.info("no media session on macOS: {}", it.message)
                objc.close()
                null
            }
        }
    }
}
