package dev.hivens.libsound.session.smtc

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
 * Our own media session on Windows, through the System Media Transport Controls.
 *
 * What turns a process that happens to be playing into a player the desktop
 * knows about: the title and artwork on the lock screen and in the volume flyout,
 * and the media keys on a keyboard.
 *
 * ## It belongs to a window
 *
 * Windows offers a desktop process one way in --
 * `ISystemMediaTransportControlsInterop::GetForWindow` -- so a session here is
 * attached to a window whether the application thinks in those terms or not.
 * [SessionConfig.windowHandle] is that window. When a consumer has none, this
 * makes one and never shows it, which is what keeps a command line tool or a
 * service from being shut out of media keys entirely.
 *
 * A window the application already owns is preferred, and not out of tidiness:
 * it is the one the user is looking at, and the one Windows will associate the
 * controls with most reliably.
 */
internal class SmtcSession private constructor(
    private val rt: WinRt,
    private val controls: MemorySegment,
    /** Non-null only when this class made it, and then it is ours to destroy. */
    private val ownWindow: MemorySegment?,
) : MediaSession {

    private val log = LoggerFactory.getLogger("libsound.Session")

    private val closed = AtomicBoolean(false)

    private val handlers = CopyOnWriteArrayList<(SessionCommand) -> Unit>()

    /**
     * One update at a time.
     *
     * A publish walks a chain of interfaces, and two publishes interleaving
     * would release each other's. A player updating position on a timer while a
     * track change publishes metadata is the ordinary case, not a contrived one.
     */
    private val updating = ReentrantLock()

    /** Button presses arrive on a thread the runtime owns; handlers never run there. */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-smtc-events").apply { isDaemon = true }
    }

    private var handlerObject: MemorySegment = MemorySegment.NULL
    private var handlerToken: Long = 0

    override val capabilities: Capabilities = Capabilities.of(Capability.SESSION_PUBLISH)

    override val isOpen: Boolean get() = !closed.get()

    override fun publish(state: SessionState) {
        if (closed.get()) return
        rt.ensureApartment()
        updating.withLock {
            runCatching {
                putStatus(state.playback)
                putFlags(state)
                putMetadata(state)
                putTimeline(state)
            }.onFailure { log.warn("could not publish the session state: {}", it.message) }
        }
    }

    override fun seeked(positionMicros: Long) {
        if (closed.get()) return
        rt.ensureApartment()
        // Only the position moved, so only the timeline is rewritten. Republishing
        // the metadata would make the lock screen redraw its artwork on every
        // scrub, which is visible.
        updating.withLock {
            runCatching { putTimeline(positionMicros = positionMicros, durationMicros = null) }
                .onFailure { log.debug("could not announce the seek: {}", it.message) }
        }
    }

    override fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handlers.clear()
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { removeButtonHandler() }
        // Disabled before release: the controls outlive this object in the
        // shell, and one left enabled is a player the desktop still offers to
        // drive after the application stopped answering.
        runCatching { putEnabled(false) }
        rt.release(controls)
        ownWindow?.let { rt.destroyWindow(it) }
        rt.close()
    }

    // -- publishing ------------------------------------------------------------

    private fun putStatus(playback: PlaybackState) {
        val status = when (playback) {
            PlaybackState.PLAYING -> SmtcAbi.PLAYBACK_STATUS_PLAYING
            PlaybackState.PAUSED -> SmtcAbi.PLAYBACK_STATUS_PAUSED
            PlaybackState.STOPPED -> SmtcAbi.PLAYBACK_STATUS_STOPPED
        }
        call(SmtcAbi.CONTROLS_PUT_PLAYBACK_STATUS, status)
    }

    private fun putEnabled(enabled: Boolean) {
        call(SmtcAbi.CONTROLS_PUT_IS_ENABLED, if (enabled) 1 else 0)
    }

    /**
     * Which buttons the desktop may offer.
     *
     * Driven by what the state says the player will accept, so a widget does not
     * draw a next-track button for a player that has no next track. The same
     * reasoning as the capability query one level up.
     */
    private fun putFlags(state: SessionState) {
        putEnabled(true)
        call(SmtcAbi.CONTROLS_PUT_IS_PLAY_ENABLED, if (state.canPlay) 1 else 0)
        call(SmtcAbi.CONTROLS_PUT_IS_PAUSE_ENABLED, if (state.canPause) 1 else 0)
        call(SmtcAbi.CONTROLS_PUT_IS_STOP_ENABLED, 1)
        call(SmtcAbi.CONTROLS_PUT_IS_NEXT_ENABLED, if (state.canGoNext) 1 else 0)
        call(SmtcAbi.CONTROLS_PUT_IS_PREVIOUS_ENABLED, if (state.canGoPrevious) 1 else 0)
    }

    private fun putMetadata(state: SessionState) {
        val updater = query(controls, SmtcAbi.CONTROLS_GET_DISPLAY_UPDATER) ?: return
        try {
            invoke(updater, SmtcAbi.UPDATER_PUT_TYPE, ValueLayout.JAVA_INT, SmtcAbi.MEDIA_TYPE_MUSIC)
            val music = query(updater, SmtcAbi.UPDATER_GET_MUSIC_PROPERTIES)
            if (music != null) {
                try {
                    Arena.ofConfined().use { call ->
                        putString(call, music, SmtcAbi.MUSIC_PUT_TITLE, state.metadata.title.orEmpty())
                        putString(call, music, SmtcAbi.MUSIC_PUT_ARTIST, state.metadata.artists.firstOrNull().orEmpty())
                        putString(
                            call, music, SmtcAbi.MUSIC_PUT_ALBUM_ARTIST,
                            state.metadata.albumArtists.firstOrNull() ?: state.metadata.album.orEmpty(),
                        )
                    }
                } finally {
                    rt.release(music)
                }
            }
            // Nothing reaches the screen until this runs; the setters above only
            // stage it.
            rt.method(updater, SmtcAbi.UPDATER_UPDATE, FunctionDescriptor.of(I32, ADDR))
                .invokeExact(updater) as Int
        } finally {
            rt.release(updater)
        }
    }

    private fun putTimeline(state: SessionState) =
        putTimeline(state.positionMicros, state.metadata.durationMicros)

    /**
     * The position a widget draws its scrubber from.
     *
     * A WinRT `TimeSpan` counts hundred-nanosecond ticks, so the microseconds
     * this library speaks in are multiplied rather than passed through. The
     * struct is eight bytes and travels in a register, which is why it is bound
     * as a long rather than by layout.
     */
    private fun putTimeline(positionMicros: Long, durationMicros: Long?) {
        val controls2 = rt.queryInterface(controls, SmtcAbi.IID_CONTROLS2) ?: return
        val timeline = activateTimeline() ?: run {
            rt.release(controls2)
            return
        }
        try {
            invoke(timeline, SmtcAbi.TIMELINE_PUT_START_TIME, ValueLayout.JAVA_LONG, 0L)
            durationMicros?.let { invoke(timeline, SmtcAbi.TIMELINE_PUT_END_TIME, ValueLayout.JAVA_LONG, it * TICKS_PER_MICRO) }
            invoke(timeline, SmtcAbi.TIMELINE_PUT_POSITION, ValueLayout.JAVA_LONG, positionMicros * TICKS_PER_MICRO)
            rt.method(
                controls2, SmtcAbi.CONTROLS2_UPDATE_TIMELINE_PROPERTIES,
                FunctionDescriptor.of(I32, ADDR, ADDR),
            ).invokeExact(controls2, timeline) as Int
        } finally {
            rt.release(timeline)
            rt.release(controls2)
        }
    }

    private fun activateTimeline(): MemorySegment? {
        val factory = rt.activationFactory(
            SmtcAbi.CLASS_TIMELINE_PROPERTIES, SmtcAbi.IID_ACTIVATION_FACTORY,
        ) ?: return null
        try {
            Arena.ofConfined().use { call ->
                val out = call.allocate(ADDR)
                val hr = rt.method(
                    factory, SmtcAbi.ACTIVATION_FACTORY_ACTIVATE_INSTANCE,
                    FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(factory, out) as Int
                if (hr != WinRt.S_OK) return null
                val instance = out.get(ADDR, 0)
                if (instance.address() == 0L) return null
                return rt.queryInterface(instance, SmtcAbi.IID_TIMELINE_PROPERTIES).also { rt.release(instance) }
            }
        } finally {
            rt.release(factory)
        }
    }

    // -- the button handler, which we implement --------------------------------

    // Public rather than internal although nothing outside calls them: Kotlin
    // mangles an internal name and findVirtual looks up what is written.

    fun onQueryInterface(self: MemorySegment, unusedIid: MemorySegment, out: MemorySegment): Int = runCatching {
        out.reinterpret(ADDR.byteSize()).set(ADDR, 0, self)
        WinRt.S_OK
    }.getOrDefault(E_FAIL)

    /** The object lives in an arena closed at teardown, after the handler is removed. */
    fun onAddRef(unusedSelf: MemorySegment): Int = 1

    fun onRelease(unusedSelf: MemorySegment): Int = 1

    fun onButtonPressed(unusedSelf: MemorySegment, unusedSender: MemorySegment, args: MemorySegment): Int {
        val button = runCatching {
            Arena.ofConfined().use { call ->
                val out = call.allocate(I32)
                val hr = rt.method(args, SmtcAbi.BUTTON_ARGS_GET_BUTTON, FunctionDescriptor.of(I32, ADDR, ADDR))
                    .invokeExact(args, out) as Int
                if (hr == WinRt.S_OK) out.get(I32, 0) else null
            }
        }.getOrNull() ?: return WinRt.S_OK

        val command = when (button) {
            SmtcAbi.BUTTON_PLAY -> SessionCommand.Play
            SmtcAbi.BUTTON_PAUSE -> SessionCommand.Pause
            SmtcAbi.BUTTON_STOP -> SessionCommand.Stop
            SmtcAbi.BUTTON_NEXT -> SessionCommand.Next
            SmtcAbi.BUTTON_PREVIOUS -> SessionCommand.Previous
            // Record, fast forward, the channel keys. Nothing here maps them, and
            // inventing a meaning would be worse than ignoring them.
            else -> return WinRt.S_OK
        }
        val snapshot = handlers.toList()
        if (snapshot.isEmpty()) return WinRt.S_OK
        runCatching {
            // Off the runtime's thread before the consumer sees it: the natural
            // response is to call back into this session, and doing that from
            // inside the runtime's own callback is how a process deadlocks itself.
            dispatch.execute {
                snapshot.forEach { handler ->
                    runCatching { handler(command) }.onFailure { log.warn("command handler threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("button dropped, dispatcher is shut down") }
        return WinRt.S_OK
    }

    private fun installButtonHandler() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val arena = rt.arena

        fun stub(name: String, type: MethodType, descriptor: FunctionDescriptor): MemorySegment =
            linker.upcallStub(lookup.findVirtual(SmtcSession::class.java, name, type).bindTo(this), descriptor, arena)

        val slots = arrayOfNulls<MemorySegment>(SmtcAbi.HANDLER_VTABLE_SLOTS)
        slots[SmtcAbi.QUERY_INTERFACE] = stub(
            "onQueryInterface",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR),
        )
        slots[SmtcAbi.ADD_REF] = stub(
            "onAddRef",
            MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.of(I32, ADDR),
        )
        slots[SmtcAbi.RELEASE] = stub(
            "onRelease",
            MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.of(I32, ADDR),
        )
        slots[SmtcAbi.HANDLER_INVOKE] = stub(
            "onButtonPressed",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR),
        )

        // Every slot filled: a short vtable means the runtime calls through
        // whatever memory happens to follow it.
        val vtable = arena.allocate(ADDR, SmtcAbi.HANDLER_VTABLE_SLOTS.toLong())
        slots.forEachIndexed { index, stubPointer ->
            vtable.setAtIndex(ADDR, index.toLong(), checkNotNull(stubPointer) { "vtable slot $index unfilled" })
        }
        val instance = arena.allocate(ADDR, 1)
        instance.set(ADDR, 0, vtable)
        handlerObject = instance

        Arena.ofConfined().use { call ->
            val token = call.allocate(ValueLayout.JAVA_LONG)
            val hr = rt.method(
                controls, SmtcAbi.CONTROLS_ADD_BUTTON_PRESSED,
                FunctionDescriptor.of(I32, ADDR, ADDR, ADDR),
            ).invokeExact(controls, instance, token) as Int
            if (hr != WinRt.S_OK) {
                log.info("media keys unavailable: 0x{}", Integer.toHexString(hr))
                handlerObject = MemorySegment.NULL
            } else {
                handlerToken = token.get(ValueLayout.JAVA_LONG, 0)
            }
        }
    }

    private fun removeButtonHandler() {
        if (handlerObject.address() == 0L) return
        handlerObject = MemorySegment.NULL
        rt.method(
            controls, SmtcAbi.CONTROLS_REMOVE_BUTTON_PRESSED,
            FunctionDescriptor.of(I32, ADDR, ValueLayout.JAVA_LONG),
        ).invokeExact(controls, handlerToken) as Int
    }

    // -- small helpers ---------------------------------------------------------

    private fun call(slot: Int, value: Int) {
        invoke(controls, slot, ValueLayout.JAVA_INT, value)
    }

    private fun invoke(iface: MemorySegment, slot: Int, layout: ValueLayout, value: Any) {
        val handle = rt.method(iface, slot, FunctionDescriptor.of(I32, ADDR, layout))
        when (value) {
            is Int -> handle.invokeExact(iface, value) as Int
            is Long -> handle.invokeExact(iface, value) as Int
            else -> error("unsupported argument $value")
        }
    }

    private fun putString(arena: Arena, iface: MemorySegment, slot: Int, text: String) {
        rt.withHString(arena, text) { string ->
            rt.method(iface, slot, FunctionDescriptor.of(I32, ADDR, ADDR)).invokeExact(iface, string) as Int
        }
    }

    private fun query(iface: MemorySegment, slot: Int): MemorySegment? = Arena.ofConfined().use { call ->
        val out = call.allocate(ADDR)
        val hr = rt.method(iface, slot, FunctionDescriptor.of(I32, ADDR, ADDR)).invokeExact(iface, out) as Int
        if (hr != WinRt.S_OK) null else out.get(ADDR, 0).takeIf { it.address() != 0L }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Session")

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT

        private const val E_FAIL = 0x8000_4005.toInt()

        /** A WinRT TimeSpan counts hundred-nanosecond ticks. */
        private const val TICKS_PER_MICRO = 10L

        /**
         * Open a session, or null where Windows will not give one.
         *
         * Null rather than an exception, and for the same reason as everywhere
         * else here: a session is a convenience, and an application that cannot
         * publish one should still play audio.
         */
        fun openOrNull(config: SessionConfig): MediaSession? {
            val rt = WinRt.loadOrNull() ?: return null
            var ownWindow: MemorySegment? = null
            return runCatching {
                rt.ensureApartment()
                val interop = rt.activationFactory(
                    SmtcAbi.CLASS_SYSTEM_MEDIA_TRANSPORT_CONTROLS, SmtcAbi.IID_INTEROP,
                ) ?: throw IllegalStateException("no transport controls on this system")

                val window = config.windowHandle
                    ?.let { MemorySegment.ofAddress(it) }
                    ?: rt.createOwnWindow(config.identity).also { ownWindow = it }
                if (window.address() == 0L) throw IllegalStateException("no window to attach the session to")

                val controls = try {
                    Arena.ofConfined().use { call ->
                        val out = call.allocate(ADDR)
                        val hr = rt.method(
                            interop, SmtcAbi.INTEROP_GET_FOR_WINDOW,
                            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR),
                        ).invokeExact(interop, window, WinRt.guid(call, SmtcAbi.IID_CONTROLS), out) as Int
                        if (hr != WinRt.S_OK) {
                            throw IllegalStateException("GetForWindow: 0x" + Integer.toHexString(hr))
                        }
                        out.get(ADDR, 0)
                    }
                } finally {
                    rt.release(interop)
                }
                if (controls.address() == 0L) throw IllegalStateException("GetForWindow returned nothing")

                SmtcSession(rt, controls, ownWindow).apply { installButtonHandler() }
            }.getOrElse {
                log.info("no media session on Windows: {}", it.message)
                ownWindow?.let { window -> rt.destroyWindow(window) }
                rt.close()
                null
            }
        }
    }
}
