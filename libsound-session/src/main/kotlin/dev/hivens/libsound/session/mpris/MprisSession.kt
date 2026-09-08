package dev.hivens.libsound.session.mpris

import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.dbus.DBusAbi
import dev.hivens.libsound.dbus.DBusConnection
import dev.hivens.libsound.dbus.DictWriter
import dev.hivens.libsound.dbus.allocateUtf8
import dev.hivens.libsound.dbus.appendDouble
import dev.hivens.libsound.dbus.appendInt64
import dev.hivens.libsound.dbus.appendString
import dev.hivens.libsound.dbus.appendVariantBoolean
import dev.hivens.libsound.dbus.appendVariantDouble
import dev.hivens.libsound.dbus.appendVariantInt64
import dev.hivens.libsound.dbus.appendVariantString
import dev.hivens.libsound.dbus.appendVariantStringArray
import dev.hivens.libsound.dbus.argType
import dev.hivens.libsound.dbus.closeContainer
import dev.hivens.libsound.dbus.dict
import dev.hivens.libsound.dbus.next
import dev.hivens.libsound.dbus.openContainer
import dev.hivens.libsound.dbus.readBoolean
import dev.hivens.libsound.dbus.readCString
import dev.hivens.libsound.dbus.readDouble
import dev.hivens.libsound.dbus.readInt64
import dev.hivens.libsound.dbus.readString
import dev.hivens.libsound.dbus.recurseOrNull
import dev.hivens.libsound.dbus.variant
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Our own session, on the bus.
 *
 * Owns `org.mpris.MediaPlayer2.<application>`, answers on the single object path
 * the spec fixes, and turns what the desktop asks into [SessionCommand]. What
 * starts working when this is up: media keys, `playerctl`, and the media widget
 * in whatever shell is running.
 *
 * ## A service without upcall stubs
 *
 * libdbus can be handed a function pointer for an object path, which would mean
 * an arena whose lifetime has to outlive a thread we do not own. Both siblings
 * declined that and pulled messages off the connection instead, and so does
 * this: [DBusConnection] hands every incoming message to the handler registered
 * below, on its own thread, and the answer goes back through the same queue.
 *
 * ## Answering is not optional
 *
 * A method call left unanswered blocks its caller until that caller's own
 * timeout, which is twenty-five seconds by default. Desktops probe players
 * before subscribing to them, so silence on an unknown member is not a harmless
 * omission -- it is a shell that hangs on startup. Everything reachable answers,
 * with an error if there is nothing better to say.
 */
internal class MprisSession private constructor(
    private val bus: DBusConnection,
    private val config: SessionConfig,
    private val busName: String,
) : MediaSession {

    private val log = LoggerFactory.getLogger("libsound.Mpris")

    private val symbols = bus.symbols

    private val closed = AtomicBoolean(false)

    private val handlers = CopyOnWriteArrayList<(SessionCommand) -> Unit>()

    @Volatile
    private var state = SessionState()

    /** What the last emission said, so only real changes are announced. */
    @Volatile
    private var published: SessionState? = null

    override val capabilities: Capabilities = Capabilities.of(Capability.SESSION_PUBLISH)

    override val isOpen: Boolean get() = !closed.get() && bus.isOpen

    override fun publish(state: SessionState) {
        if (closed.get()) return
        val previous = published
        this.state = state
        published = state
        if (previous == null) {
            // Nothing has been said yet, so everything is news.
            emitChanged(Mpris.PLAYER_INTERFACE, carried(Mpris.PLAYER_CHANGING_PROPERTIES, state), emptyList(), state)
            emitChanged(Mpris.ROOT_INTERFACE, carried(Mpris.ROOT_CHANGING_PROPERTIES, state), emptyList(), state)
            return
        }
        announce(Mpris.PLAYER_INTERFACE, Mpris.PLAYER_CHANGING_PROPERTIES, previous, state)
        announce(Mpris.ROOT_INTERFACE, Mpris.ROOT_CHANGING_PROPERTIES, previous, state)
    }

    override fun seeked(positionMicros: Long) {
        if (closed.get()) return
        state = state.copy(positionMicros = positionMicros)
        published = state
        Arena.ofConfined().use { call ->
            val signal = newSignal(call, Mpris.PLAYER_INTERFACE, "Seeked") ?: return
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.handle("dbus_message_iter_init_append").invokeExact(signal, iter) as Unit
            symbols.appendInt64(call, iter, positionMicros)
            bus.send(signal)
        }
    }

    override fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { bus.releaseName(busName) }
        bus.close()
    }

    // -- incoming --------------------------------------------------------------

    /** Returns true when the message was ours to answer. */
    private fun handle(message: MemorySegment): Boolean {
        val type = symbols.handle("dbus_message_get_type").invokeExact(message) as Int
        if (type != DBusAbi.MESSAGE_TYPE_METHOD_CALL) return false

        val path = readMessageString("dbus_message_get_path", message)
        val iface = readMessageString("dbus_message_get_interface", message)
        val member = readMessageString("dbus_message_get_member", message) ?: return false

        // The spec fixes one path. A call routed elsewhere is not ours, and
        // answering it would claim an object we do not have.
        if (path != Mpris.OBJECT_PATH) return false

        return when (iface) {
            Mpris.PROPERTIES_INTERFACE -> {
                when (member) {
                    "Get" -> handleGet(message)
                    "GetAll" -> handleGetAll(message)
                    "Set" -> handleSet(message)
                    else -> replyUnknownMethod(message, iface, member)
                }
                true
            }
            Mpris.INTROSPECTABLE_INTERFACE -> {
                if (member == "Introspect") replyIntrospection(message)
                else replyUnknownMethod(message, iface, member)
                true
            }
            Mpris.PEER_INTERFACE -> {
                // Ping is how a desktop checks we are still here. Answering it
                // costs nothing; not answering it looks like a hung player.
                if (member == "Ping") replyEmpty(message) else replyUnknownMethod(message, iface, member)
                true
            }
            Mpris.ROOT_INTERFACE -> {
                handleRootMethod(message, member)
                true
            }
            Mpris.PLAYER_INTERFACE -> {
                handlePlayerMethod(message, member)
                true
            }
            else -> false
        }
    }

    private fun handleRootMethod(message: MemorySegment, member: String) {
        val command: SessionCommand? = when (member) {
            // Each is gated on what the configuration advertised. The spec says
            // a call made where CanRaise or CanQuit is false has no effect, and
            // a consumer that claimed neither should not be handed a command it
            // has no reason to expect. Answered either way: silence is a shell
            // that hangs rather than a call that was ignored.
            "Raise" -> SessionCommand.Raise.takeIf { config.canRaise }
            "Quit" -> SessionCommand.Quit.takeIf { config.canQuit }
            else -> {
                replyUnknownMethod(message, Mpris.ROOT_INTERFACE, member)
                return
            }
        }
        replyEmpty(message)
        command?.let { fire(it) }
    }

    private fun handlePlayerMethod(message: MemorySegment, member: String) {
        val command: SessionCommand? = when (member) {
            "Play" -> SessionCommand.Play
            "Pause" -> SessionCommand.Pause
            "PlayPause" -> SessionCommand.PlayPause
            "Stop" -> SessionCommand.Stop
            "Next" -> SessionCommand.Next
            "Previous" -> SessionCommand.Previous
            "Seek" -> readSeek(message)
            "SetPosition" -> readSetPosition(message)
            // Advertised in the introspection XML because the spec puts it on
            // the interface, and refused here because nothing acts on it. An
            // empty reply is worse than an error: it tells a desktop the uri
            // was opened, and the track it thinks is playing never starts.
            "OpenUri" -> {
                replyError(message, ERROR_NOT_SUPPORTED, "This player does not open uris")
                return
            }
            else -> {
                replyUnknownMethod(message, Mpris.PLAYER_INTERFACE, member)
                return
            }
        }
        // The reply goes first. A handler is the consumer's code and may take
        // its time; the caller is blocked until we answer, and making a desktop
        // wait on a launcher's UI thread is how a shell freezes.
        replyEmpty(message)
        command?.let { fire(it) }
    }

    private fun readSeek(message: MemorySegment): SessionCommand? = Arena.ofConfined().use { call ->
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return null
        symbols.readInt64(call, iter)?.let { SessionCommand.Seek(it) }
    }

    private fun readSetPosition(message: MemorySegment): SessionCommand? = Arena.ofConfined().use { call ->
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return null
        val trackId = symbols.readString(call, iter)
        symbols.next(iter)
        val position = symbols.readInt64(call, iter) ?: return null
        // The track id is carried so a stale command can be dropped: a desktop
        // may send a seek for the track it last saw, and by then we may be
        // playing the next one.
        SessionCommand.SetPosition(trackId, position)
    }

    // -- properties ------------------------------------------------------------

    private fun handleGet(message: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) {
                replyError(message, ERROR_INVALID_ARGS, "Get takes two strings")
                return
            }
            val iface = symbols.readString(call, iter)
            symbols.next(iter)
            val property = symbols.readString(call, iter)
            if (iface == null || property == null) {
                replyError(message, ERROR_INVALID_ARGS, "Get takes two strings")
                return
            }
            val reply = newReturn(message) ?: return
            val replyIter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit
            if (!appendProperty(call, replyIter, iface, property, state)) {
                symbols.handle("dbus_message_unref").invokeExact(reply) as Unit
                replyError(message, ERROR_UNKNOWN_PROPERTY, "No such property $iface.$property")
                return
            }
            bus.send(reply)
        }
    }

    private fun handleGetAll(message: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) {
                replyError(message, ERROR_INVALID_ARGS, "GetAll takes an interface name")
                return
            }
            val iface = symbols.readString(call, iter)
            // One snapshot for both halves of the answer. The list is filtered
            // by what the state carries and the values are read from the same
            // state, because a publish landing between the two would leave a
            // dictionary entry with a key and no value, which is a malformed
            // message rather than a missing field.
            val current = state
            val names = when (iface) {
                Mpris.ROOT_INTERFACE -> ROOT_PROPERTIES
                Mpris.PLAYER_INTERFACE -> PLAYER_PROPERTIES
                else -> {
                    // An empty dictionary rather than an error: GetAll on an
                    // interface we do not carry is a legitimate question with
                    // "nothing" as its answer.
                    emptyList()
                }
            }.filter { has(it, current) }
            val reply = newReturn(message) ?: return
            val replyIter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.handle("dbus_message_iter_init_append").invokeExact(reply, replyIter) as Unit
            symbols.dict(call, replyIter) { entries ->
                names.forEach { writeProperty(call, entries, iface ?: "", it, current) }
            }
            bus.send(reply)
        }
    }

    private fun handleSet(message: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) {
                replyError(message, ERROR_INVALID_ARGS, "Set takes an interface, a property and a value")
                return
            }
            val iface = symbols.readString(call, iter)
            symbols.next(iter)
            val property = symbols.readString(call, iter)
            symbols.next(iter)
            val current = state

            // The interface is read rather than skipped now that both of them
            // carry a writable property: Fullscreen belongs to the root and
            // Volume to the player, and a Set aimed at the wrong one is asking
            // for something that is not there. The second half of the same
            // question is whether this session carries the property at all,
            // since the optional four are absent until a consumer publishes
            // them.
            if (property == null || property !in writableOn(iface) || !has(property, current)) {
                replyError(message, ERROR_UNKNOWN_PROPERTY, "No writable property ${iface ?: "?"}.$property")
                return
            }
            // Setting a property is controlling the player, and CanControl is
            // this session's answer to whether it may be controlled at all. An
            // empty reply where nothing is listening tells a widget the change
            // took, and it draws the state it asked for over a player that
            // never heard the request, which is the argument the OpenUri branch
            // above makes for refusing rather than answering politely.
            if (iface == Mpris.PLAYER_INTERFACE && handlers.isEmpty()) {
                replyError(message, ERROR_NOT_SUPPORTED, "CanControl is false: nothing is listening")
                return
            }
            // The value is the third argument and nothing has established that
            // there is one. Recursing into an iterator that ran out asserts
            // inside libdbus, which aborts the process, so the shape is checked
            // before it is read.
            val value = symbols.recurseOrNull(call, iter)
            if (value == null) {
                replyError(message, ERROR_INVALID_ARGS, "Set takes an interface, a property and a value")
                return
            }
            when (property) {
                Mpris.PROP_VOLUME -> {
                    val volume = symbols.readDouble(call, value)
                    if (volume == null) {
                        replyError(message, ERROR_INVALID_ARGS, "Volume is a double")
                        return
                    }
                    replyEmpty(message)
                    fire(SessionCommand.SetVolume(volume.coerceIn(0.0, 1.0)))
                }
                Mpris.PROP_LOOP_STATUS -> {
                    val mode = Mpris.modeOf(symbols.readString(call, value))
                    if (mode == null) {
                        replyError(message, ERROR_INVALID_ARGS, "LoopStatus is None, Track or Playlist")
                        return
                    }
                    replyEmpty(message)
                    fire(SessionCommand.SetLoop(mode))
                }
                Mpris.PROP_SHUFFLE -> {
                    val shuffle = symbols.readBoolean(call, value)
                    if (shuffle == null) {
                        replyError(message, ERROR_INVALID_ARGS, "Shuffle is a boolean")
                        return
                    }
                    replyEmpty(message)
                    fire(SessionCommand.SetShuffle(shuffle))
                }
                Mpris.PROP_FULLSCREEN -> {
                    // The property is readable wherever there is a screen state
                    // to report and writable only where the consumer said the
                    // desktop may change it, which is what CanSetFullscreen
                    // advertises beside it.
                    if (!config.canSetFullscreen) {
                        replyError(message, ERROR_NOT_SUPPORTED, "CanSetFullscreen is false")
                        return
                    }
                    val fullscreen = symbols.readBoolean(call, value)
                    if (fullscreen == null) {
                        replyError(message, ERROR_INVALID_ARGS, "Fullscreen is a boolean")
                        return
                    }
                    replyEmpty(message)
                    fire(SessionCommand.SetFullscreen(fullscreen))
                }
                // Rate is writable per the spec and refused here, because the
                // library reports playback rate and does not set it -- saying so
                // is better than accepting a value nothing acts on.
                Mpris.PROP_RATE -> replyError(
                    message, ERROR_NOT_SUPPORTED, "Rate is read-only in this player",
                )
                // Unreachable while every name in writableOn has a branch
                // above, and kept because the cost of the two disagreeing is
                // not a wrong answer but no answer, and an unanswered call
                // blocks its caller for twenty-five seconds.
                else -> replyError(message, ERROR_NOT_SUPPORTED, "$property cannot be set here")
            }
        }
    }

    /** Writes the variant for one property, or returns false when there is no such property. */
    private fun appendProperty(
        call: Arena,
        parent: MemorySegment,
        iface: String,
        name: String,
        current: SessionState,
    ): Boolean {
        when (iface) {
            Mpris.ROOT_INTERFACE -> when (name) {
                Mpris.PROP_IDENTITY -> symbols.appendVariantString(call, parent, config.identity)
                Mpris.PROP_DESKTOP_ENTRY -> symbols.appendVariantString(call, parent, config.desktopEntry ?: "")
                Mpris.PROP_CAN_QUIT -> symbols.appendVariantBoolean(call, parent, config.canQuit)
                Mpris.PROP_CAN_RAISE -> symbols.appendVariantBoolean(call, parent, config.canRaise)
                // Both halves of the pair answer only where there is a screen
                // state to report, so a player with no window advertises
                // neither rather than advertising that it cannot be resized.
                Mpris.PROP_FULLSCREEN ->
                    symbols.appendVariantBoolean(call, parent, current.fullscreen ?: return false)
                Mpris.PROP_CAN_SET_FULLSCREEN -> {
                    if (current.fullscreen == null) return false
                    symbols.appendVariantBoolean(call, parent, config.canSetFullscreen)
                }
                Mpris.PROP_HAS_TRACK_LIST -> symbols.appendVariantBoolean(call, parent, false)
                Mpris.PROP_SUPPORTED_URI_SCHEMES ->
                    symbols.appendVariantStringArray(call, parent, emptyList())
                Mpris.PROP_SUPPORTED_MIME_TYPES ->
                    symbols.appendVariantStringArray(call, parent, emptyList())
                else -> return false
            }
            Mpris.PLAYER_INTERFACE -> when (name) {
                Mpris.PROP_PLAYBACK_STATUS ->
                    symbols.appendVariantString(call, parent, Mpris.statusOf(current.playback))
                Mpris.PROP_LOOP_STATUS ->
                    symbols.appendVariantString(call, parent, Mpris.loopOf(current.loop ?: return false))
                Mpris.PROP_SHUFFLE ->
                    symbols.appendVariantBoolean(call, parent, current.shuffle ?: return false)
                Mpris.PROP_METADATA -> symbols.variant(call, parent, "a{sv}") { inner ->
                    symbols.dict(call, inner) { entries -> writeMetadata(entries, current) }
                }
                Mpris.PROP_POSITION -> symbols.appendVariantInt64(call, parent, current.positionMicros)
                Mpris.PROP_VOLUME -> symbols.appendVariantDouble(call, parent, current.volume)
                Mpris.PROP_RATE -> symbols.appendVariantDouble(call, parent, current.rate)
                Mpris.PROP_MINIMUM_RATE -> symbols.appendVariantDouble(call, parent, 1.0)
                Mpris.PROP_MAXIMUM_RATE -> symbols.appendVariantDouble(call, parent, 1.0)
                Mpris.PROP_CAN_GO_NEXT -> symbols.appendVariantBoolean(call, parent, current.canGoNext)
                Mpris.PROP_CAN_GO_PREVIOUS -> symbols.appendVariantBoolean(call, parent, current.canGoPrevious)
                Mpris.PROP_CAN_PLAY -> symbols.appendVariantBoolean(call, parent, current.canPlay)
                Mpris.PROP_CAN_PAUSE -> symbols.appendVariantBoolean(call, parent, current.canPause)
                Mpris.PROP_CAN_SEEK -> symbols.appendVariantBoolean(call, parent, current.canSeek)
                // CanControl false tells a widget to draw nothing rather than
                // draw buttons that do nothing, so it follows whether anything
                // is listening at all.
                Mpris.PROP_CAN_CONTROL -> symbols.appendVariantBoolean(call, parent, handlers.isNotEmpty())
                else -> return false
            }
            else -> return false
        }
        return true
    }

    private fun writeProperty(
        call: Arena,
        entries: DictWriter,
        iface: String,
        name: String,
        current: SessionState,
    ) {
        entries.raw(name) { parent -> appendProperty(call, parent, iface, name, current) }
    }

    /**
     * Whether the session currently carries [property] at all.
     *
     * Four properties in the specification are optional, and a consumer that
     * published nothing for them has none: a widget draws a repeat button for a
     * player that has LoopStatus and leaves it out for one that does not, so
     * answering "None" would put a dead button on every player this library
     * publishes. The answer has to be the same one everywhere, which is why
     * GetAll, Get, Set, the change signal and the introspection all ask here.
     */
    private fun has(property: String, current: SessionState): Boolean = when (property) {
        Mpris.PROP_LOOP_STATUS -> current.loop != null
        Mpris.PROP_SHUFFLE -> current.shuffle != null
        Mpris.PROP_FULLSCREEN, Mpris.PROP_CAN_SET_FULLSCREEN -> current.fullscreen != null
        else -> true
    }

    private fun carried(properties: List<String>, current: SessionState): List<String> =
        properties.filter { has(it, current) }

    /** The properties [iface] accepts a `Set` for, whether or not this session carries them. */
    private fun writableOn(iface: String?): Set<String> = when (iface) {
        Mpris.ROOT_INTERFACE -> setOf(Mpris.PROP_FULLSCREEN)
        Mpris.PLAYER_INTERFACE ->
            setOf(Mpris.PROP_VOLUME, Mpris.PROP_RATE, Mpris.PROP_LOOP_STATUS, Mpris.PROP_SHUFFLE)
        else -> emptySet()
    }

    private fun replyIntrospection(message: MemorySegment) {
        val current = state
        replyString(
            message,
            Mpris.introspectionXml(
                loop = current.loop != null,
                shuffle = current.shuffle != null,
                fullscreen = current.fullscreen != null,
            ),
        )
    }

    private fun writeMetadata(entries: DictWriter, current: SessionState) {
        val metadata = current.metadata
        // trackid is an object path and always present: readers use it to tell
        // one track from the next, and an absent one makes every update look
        // like the same track changing under them.
        entries.objectPath(Mpris.KEY_TRACK_ID, Mpris.trackPath(metadata.trackId))
        entries.string(Mpris.KEY_TITLE, metadata.title)
        entries.stringArray(Mpris.KEY_ARTIST, metadata.artists)
        entries.string(Mpris.KEY_ALBUM, metadata.album)
        entries.stringArray(Mpris.KEY_ALBUM_ARTIST, metadata.albumArtists)
        entries.int64(Mpris.KEY_LENGTH, metadata.durationMicros)
        entries.string(Mpris.KEY_ART_URL, metadata.artUrl)
        entries.int64(Mpris.KEY_TRACK_NUMBER, metadata.trackNumber?.toLong())
    }

    private fun differs(property: String, before: SessionState, after: SessionState): Boolean =
        when (property) {
            Mpris.PROP_PLAYBACK_STATUS -> before.playback != after.playback
            Mpris.PROP_LOOP_STATUS -> before.loop != after.loop
            Mpris.PROP_SHUFFLE -> before.shuffle != after.shuffle
            Mpris.PROP_FULLSCREEN -> before.fullscreen != after.fullscreen
            Mpris.PROP_METADATA -> before.metadata != after.metadata
            Mpris.PROP_VOLUME -> before.volume != after.volume
            Mpris.PROP_RATE -> before.rate != after.rate
            Mpris.PROP_CAN_GO_NEXT -> before.canGoNext != after.canGoNext
            Mpris.PROP_CAN_GO_PREVIOUS -> before.canGoPrevious != after.canGoPrevious
            Mpris.PROP_CAN_PLAY -> before.canPlay != after.canPlay
            Mpris.PROP_CAN_PAUSE -> before.canPause != after.canPause
            Mpris.PROP_CAN_SEEK -> before.canSeek != after.canSeek
            else -> false
        }

    /**
     * Announce what moved on one interface.
     *
     * A signal names the interface its properties belong to, so the root's
     * Fullscreen and the player's PlaybackStatus cannot travel together however
     * closely they changed.
     */
    private fun announce(
        iface: String,
        properties: List<String>,
        previous: SessionState,
        current: SessionState,
    ) {
        val changed = properties.filter { has(it, current) && differs(it, previous, current) }
        // A property the player has stopped carrying cannot be given a new
        // value, so it goes in the invalidated array instead. That is what the
        // array is for: the reader is told to look again, and finds it is no
        // longer there.
        val invalidated = properties.filter { has(it, previous) && !has(it, current) }
        emitChanged(iface, changed, invalidated, current)
    }

    private fun emitChanged(
        iface: String,
        changed: List<String>,
        invalidated: List<String>,
        current: SessionState,
    ) {
        if (changed.isEmpty() && invalidated.isEmpty()) return
        Arena.ofConfined().use { call ->
            val signal = newSignal(call, Mpris.PROPERTIES_INTERFACE, "PropertiesChanged") ?: return
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.handle("dbus_message_iter_init_append").invokeExact(signal, iter) as Unit
            symbols.appendString(call, iter, DBusAbi.TYPE_STRING, iface)
            symbols.dict(call, iter) { entries ->
                changed.forEach { writeProperty(call, entries, iface, it, current) }
            }
            // The invalidated array, usually empty. It is not optional even
            // then: the signature is (sa{sv}as) and a reader iterating three
            // arguments finds two.
            val names = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.openContainer(iter, DBusAbi.TYPE_ARRAY, call.allocateUtf8("s"), names)
            invalidated.forEach { symbols.appendString(call, names, DBusAbi.TYPE_STRING, it) }
            symbols.closeContainer(iter, names)
            bus.send(signal)
        }
    }

    // -- replies ---------------------------------------------------------------

    private fun newReturn(message: MemorySegment): MemorySegment? {
        val reply = symbols.handle("dbus_message_new_method_return").invokeExact(message) as MemorySegment
        return if (reply.address() == 0L) null else reply
    }

    private fun newSignal(call: Arena, iface: String, member: String): MemorySegment? {
        val signal = symbols.handle("dbus_message_new_signal").invokeExact(
            call.allocateUtf8(Mpris.OBJECT_PATH),
            call.allocateUtf8(iface),
            call.allocateUtf8(member),
        ) as MemorySegment
        return if (signal.address() == 0L) null else signal
    }

    private fun replyEmpty(message: MemorySegment) {
        newReturn(message)?.let { bus.send(it) }
    }

    private fun replyString(message: MemorySegment, value: String) {
        Arena.ofConfined().use { call ->
            val reply = newReturn(message) ?: return
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            symbols.handle("dbus_message_iter_init_append").invokeExact(reply, iter) as Unit
            symbols.appendString(call, iter, DBusAbi.TYPE_STRING, value)
            bus.send(reply)
        }
    }

    private fun replyError(message: MemorySegment, name: String, text: String) {
        Arena.ofConfined().use { call ->
            val reply = symbols.handle("dbus_message_new_error").invokeExact(
                message, call.allocateUtf8(name), call.allocateUtf8(text),
            ) as MemorySegment
            if (reply.address() != 0L) bus.send(reply)
        }
    }

    /**
     * An unanswered method call blocks its caller to that caller's own timeout,
     * twenty-five seconds by default. Desktops probe players before subscribing,
     * so silence here is a shell that hangs rather than a message that is
     * ignored. Skipped only when the caller said it wants no reply.
     */
    private fun replyUnknownMethod(message: MemorySegment, iface: String?, member: String) {
        if ((symbols.handle("dbus_message_get_no_reply").invokeExact(message) as Int) != 0) return
        replyError(message, ERROR_UNKNOWN_METHOD, "No such method ${iface ?: "?"}.$member")
    }

    private fun readMessageString(accessor: String, message: MemorySegment): String? =
        (symbols.handle(accessor).invokeExact(message) as MemorySegment).readCString()

    private fun fire(command: SessionCommand) {
        handlers.forEach { handler ->
            runCatching { handler(command) }
                .onFailure { log.warn("command handler threw: {}", it.message) }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mpris")

        private const val ERROR_UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val ERROR_UNKNOWN_PROPERTY = "org.freedesktop.DBus.Error.UnknownProperty"
        private const val ERROR_INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"
        private const val ERROR_NOT_SUPPORTED = "org.freedesktop.DBus.Error.NotSupported"

        private val ROOT_PROPERTIES = listOf(
            Mpris.PROP_CAN_QUIT, Mpris.PROP_CAN_RAISE, Mpris.PROP_HAS_TRACK_LIST,
            Mpris.PROP_IDENTITY, Mpris.PROP_DESKTOP_ENTRY,
            Mpris.PROP_SUPPORTED_URI_SCHEMES, Mpris.PROP_SUPPORTED_MIME_TYPES,
            Mpris.PROP_FULLSCREEN, Mpris.PROP_CAN_SET_FULLSCREEN,
        )

        private val PLAYER_PROPERTIES = listOf(
            Mpris.PROP_PLAYBACK_STATUS, Mpris.PROP_LOOP_STATUS, Mpris.PROP_SHUFFLE,
            Mpris.PROP_METADATA, Mpris.PROP_POSITION,
            Mpris.PROP_VOLUME, Mpris.PROP_RATE, Mpris.PROP_MINIMUM_RATE, Mpris.PROP_MAXIMUM_RATE,
            Mpris.PROP_CAN_GO_NEXT, Mpris.PROP_CAN_GO_PREVIOUS, Mpris.PROP_CAN_PLAY,
            Mpris.PROP_CAN_PAUSE, Mpris.PROP_CAN_SEEK, Mpris.PROP_CAN_CONTROL,
        )

        /**
         * Claim the bus and start answering, or return null when there is no
         * session bus or somebody already owns the name.
         *
         * Being queued behind another owner is refused rather than accepted:
         * the desktop talks to whoever holds the name, so a queued player is
         * indistinguishable from a player that is not there, except that it
         * also believes it is publishing.
         */
        fun openOrNull(config: SessionConfig): MediaSession? {
            val bus = DBusConnection.openOrNull("mpris") ?: return null
            val name = Mpris.busName(config.applicationName)
            val session = MprisSession(bus, config, name)
            bus.onMessage(session::handle)
            bus.start()
            val result = bus.requestName(name)
            if (result != DBusAbi.REQUEST_NAME_REPLY_PRIMARY_OWNER) {
                log.info("could not own {} (reply {}); no session published", name, result)
                bus.close()
                return null
            }
            log.info("media session published as {}", name)
            return session
        }
    }
}
