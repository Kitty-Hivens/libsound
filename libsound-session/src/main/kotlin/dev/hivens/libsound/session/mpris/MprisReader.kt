package dev.hivens.libsound.session.mpris

import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.ForeignPlayer
import dev.hivens.libsound.PlayerEvent
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionReader
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.dbus.DBusAbi
import dev.hivens.libsound.dbus.DBusConnection
import dev.hivens.libsound.dbus.allocateUtf8
import dev.hivens.libsound.dbus.appendInt64
import dev.hivens.libsound.dbus.appendString
import dev.hivens.libsound.dbus.appendVariantBoolean
import dev.hivens.libsound.dbus.appendVariantDouble
import dev.hivens.libsound.dbus.appendVariantString
import dev.hivens.libsound.dbus.argType
import dev.hivens.libsound.dbus.next
import dev.hivens.libsound.dbus.readBoolean
import dev.hivens.libsound.dbus.readCString
import dev.hivens.libsound.dbus.readDouble
import dev.hivens.libsound.dbus.readInt32
import dev.hivens.libsound.dbus.readInt64
import dev.hivens.libsound.dbus.readString
import dev.hivens.libsound.dbus.readStringArray
import dev.hivens.libsound.dbus.recurse
import dev.hivens.libsound.dbus.recurseOrNull
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everyone else's players.
 *
 * Lists whoever owns a name under the MPRIS prefix, reads their state, follows
 * it as it changes, and asks them to do things. The last part is control of
 * another application and is deliberately here: a player owning an MPRIS name
 * published a control surface and says through `CanControl` whether it means
 * it, which is a different arrangement from reaching into the sound server's
 * stream list.
 *
 * ## Reading is a round trip per player
 *
 * `GetAll` on two interfaces for each name on the bus. That is why [players]
 * does the work and the change subscription does not: a `PropertiesChanged`
 * arrives with the changed properties in it, so following a player costs
 * nothing after the first read, while enumerating from scratch costs a call per
 * player per interface.
 *
 * ## Its own connection
 *
 * Not the publisher's. They would work on one, but a reader that shares a
 * connection with a session sees its own signals come back and has to filter
 * them out by sender, and getting that wrong is a player that watches itself.
 */
internal class MprisReader private constructor(
    private val bus: DBusConnection,
) : SessionReader {

    private val log = LoggerFactory.getLogger("libsound.Mpris")

    private val symbols = bus.symbols

    private val closed = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<(PlayerEvent) -> Unit>()

    /** Last known state per bus name, so a change signal can be merged into it. */
    private val known = HashMap<String, ForeignPlayer>()

    /**
     * How many times a signal has changed each row, guarded by [known].
     *
     * [players] reads every player over the bus and writes the answers back
     * afterwards, and a signal that arrives in between is newer than what it is
     * about to write. Without a mark to compare, the write puts the row back to
     * before the change, a subscriber has already been told about the change,
     * and the next unrelated signal merges onto the old value and reports the
     * change undone.
     */
    private val merges = HashMap<String, Long>()

    /** Unique sender to well-known name. Fixed for as long as a player lives. */
    private val owners = ConcurrentHashMap<String, String>()

    /**
     * Events reach consumers here, never on the bus thread.
     *
     * The rule both audio backends needed: the natural response to "a player
     * changed" is to read something, and reading from the thread that would
     * deliver the answer is a loop waiting for itself.
     */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-mpris-events").apply { isDaemon = true }
    }

    override val capabilities: Capabilities = Capabilities.of(Capability.SESSION_READ)

    override val isOpen: Boolean get() = !closed.get() && bus.isOpen

    override fun players(): List<ForeignPlayer> {
        if (closed.get()) return emptyList()
        val marks = synchronized(known) { HashMap(merges) }
        val fresh = listNames()
            .filter { it.startsWith(Mpris.BUS_NAME_PREFIX) }
            .mapNotNull { read(it) }
        return synchronized(known) {
            fresh.map { player ->
                if (merges[player.id] != marks[player.id]) {
                    // Something arrived while this was reading, and it knows
                    // more than a round trip started before it did.
                    known[player.id] ?: player
                } else {
                    known[player.id] = player
                    player
                }
            }
        }
    }

    override fun control(playerId: String, command: SessionCommand): Boolean {
        if (closed.get()) return false
        return Arena.ofConfined().use { call ->
            val (iface, member, argument) = when (command) {
                SessionCommand.Play -> Triple(Mpris.PLAYER_INTERFACE, "Play", null)
                SessionCommand.Pause -> Triple(Mpris.PLAYER_INTERFACE, "Pause", null)
                SessionCommand.PlayPause -> Triple(Mpris.PLAYER_INTERFACE, "PlayPause", null)
                SessionCommand.Stop -> Triple(Mpris.PLAYER_INTERFACE, "Stop", null)
                SessionCommand.Next -> Triple(Mpris.PLAYER_INTERFACE, "Next", null)
                SessionCommand.Previous -> Triple(Mpris.PLAYER_INTERFACE, "Previous", null)
                is SessionCommand.Seek -> Triple(Mpris.PLAYER_INTERFACE, "Seek", command.offsetMicros)
                is SessionCommand.SetPosition ->
                    Triple(Mpris.PLAYER_INTERFACE, "SetPosition", command.positionMicros)
                // Raise and Quit are the root's, not the player's. Both are
                // offers the target published and advertises through CanRaise
                // and CanQuit, which is why they are here at all: this asks a
                // player to do what it said it would accept.
                SessionCommand.Raise -> Triple(Mpris.ROOT_INTERFACE, "Raise", null)
                SessionCommand.Quit -> Triple(Mpris.ROOT_INTERFACE, "Quit", null)
                // The rest are properties rather than methods, so they go
                // through Properties.Set like Volume always did.
                is SessionCommand.SetVolume ->
                    return@use setProperty(call, playerId, Mpris.PLAYER_INTERFACE, Mpris.PROP_VOLUME) {
                        symbols.appendVariantDouble(call, it, command.volume)
                    }
                is SessionCommand.SetLoop ->
                    return@use setProperty(call, playerId, Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS) {
                        symbols.appendVariantString(call, it, Mpris.loopOf(command.loop))
                    }
                is SessionCommand.SetShuffle ->
                    return@use setProperty(call, playerId, Mpris.PLAYER_INTERFACE, Mpris.PROP_SHUFFLE) {
                        symbols.appendVariantBoolean(call, it, command.shuffle)
                    }
                is SessionCommand.SetFullscreen ->
                    return@use setProperty(call, playerId, Mpris.ROOT_INTERFACE, Mpris.PROP_FULLSCREEN) {
                        symbols.appendVariantBoolean(call, it, command.fullscreen)
                    }
            }
            val message = bus.newCall(call, playerId, Mpris.OBJECT_PATH, iface, member)
                ?: return@use false
            if (argument != null) {
                val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
                symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
                if (command is SessionCommand.SetPosition) {
                    // SetPosition takes the track id first, and the player drops
                    // the command when it names a track that is no longer
                    // current -- which is the whole reason it is carried. The id
                    // read off that player is the path it published, so it goes
                    // back untouched: escaping it here named a track nobody had
                    // and every seek sent from this side was discarded.
                    symbols.appendString(
                        call, iter, DBusAbi.TYPE_OBJECT_PATH, Mpris.foreignTrackPath(command.trackId),
                    )
                }
                symbols.appendInt64(call, iter, argument)
            }
            val reply = bus.call(message) ?: return@use false
            runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
            true
        }
    }

    override fun onChange(handler: (PlayerEvent) -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        // Waited for, not merely signalled. The dispatch thread reads a player
        // it has just seen appear, and that read goes through downcall handles
        // bound to the arena bus.close() is about to free.
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        bus.close()
    }

    // -- incoming --------------------------------------------------------------

    private fun handle(message: MemorySegment): Boolean {
        if ((symbols.handle("dbus_message_get_type").invokeExact(message) as Int) != DBusAbi.MESSAGE_TYPE_SIGNAL) {
            return false
        }
        val iface = readMessageString("dbus_message_get_interface", message)
        val member = readMessageString("dbus_message_get_member", message)
        return when {
            iface == "org.freedesktop.DBus" && member == "NameOwnerChanged" -> {
                onNameOwnerChanged(message); true
            }
            iface == Mpris.PROPERTIES_INTERFACE && member == "PropertiesChanged" -> {
                onPropertiesChanged(message); true
            }
            iface == Mpris.PLAYER_INTERFACE && member == "Seeked" -> {
                onSeeked(message); true
            }
            else -> false
        }
    }

    /** A player appearing or leaving; the bus tells everyone. */
    private fun onNameOwnerChanged(message: MemorySegment) {
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return
            val name = symbols.readString(call, iter) ?: return
            if (!name.startsWith(Mpris.BUS_NAME_PREFIX)) return
            symbols.next(iter)
            val oldOwner = symbols.readString(call, iter)
            symbols.next(iter)
            val newOwner = symbols.readString(call, iter)

            // This signal carries both halves of the mapping every later
            // PropertiesChanged has to resolve, so record it here. Without it
            // the first change from each player costs a GetNameOwner round trip
            // per player already known -- on the bus thread, inside a handler
            // that is documented not to block.
            oldOwner?.takeIf { it.isNotEmpty() }?.let { owners.remove(it) }
            newOwner?.takeIf { it.isNotEmpty() }?.let { owners[it] = name }

            if (newOwner.isNullOrEmpty()) {
                synchronized(known) {
                    known.remove(name)
                    merges.remove(name)
                }
                emit(PlayerEvent.Gone(name))
                return
            }
            if (oldOwner.isNullOrEmpty()) {
                // A fresh player. Reading it here would block the bus thread on
                // a round trip to somebody who has only just arrived, so the
                // read happens on the dispatch thread with everything else.
                runCatching {
                    dispatch.execute {
                        read(name)?.let { player ->
                            synchronized(known) {
                                known[name] = player
                                merges[name] = (merges[name] ?: 0L) + 1
                            }
                            emit(PlayerEvent.Appeared(player))
                        }
                    }
                }.onFailure { log.debug("player appearance dropped, the reader is closing") }
            }
        }
    }

    private fun onPropertiesChanged(message: MemorySegment) {
        val sender = readMessageString("dbus_message_get_sender", message) ?: return
        val change = readChange(message) ?: return
        // Resolved and merged off the bus thread. Turning a sender into the
        // name it is known by can cost a round trip, and a handler that makes
        // one is a handler blocking the connection the answer has to come back
        // on, which the connection's own documentation says not to do.
        onDispatch {
            val id = knownIdForOwner(sender) ?: return@onDispatch
            val merged = synchronized(known) {
                val previous = known[id] ?: return@synchronized null
                val updated = merge(previous, change.iface, change.changed, change.invalidated)
                known[id] = updated
                merges[id] = (merges[id] ?: 0L) + 1
                updated
            } ?: return@onDispatch
            deliver(PlayerEvent.Changed(merged))
        }
    }

    /** One signal's payload, read on the bus thread and carried off it. */
    private class Change(
        val iface: String,
        val changed: Map<String, Any?>,
        val invalidated: List<String>,
    )

    private fun readChange(message: MemorySegment): Change? = Arena.ofConfined().use { call ->
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return null
        // Both interfaces, because Fullscreen is a root property and the
        // signal names the interface its properties came from.
        val iface = symbols.readString(call, iter)
        if (iface != Mpris.PLAYER_INTERFACE && iface != Mpris.ROOT_INTERFACE) return null
        symbols.next(iter)
        val changed = readVariantDict(call, iter)
        symbols.next(iter)
        // A property the player stopped carrying arrives here rather than
        // in the dictionary, since there is no new value to send. Reading
        // it is how a repeat button drawn from LoopStatus goes away again.
        val invalidated = symbols.readStringArray(call, iter)
        if (changed.isEmpty() && invalidated.isEmpty()) return null
        Change(iface, changed, invalidated)
    }

    private fun onSeeked(message: MemorySegment) {
        val sender = readMessageString("dbus_message_get_sender", message) ?: return
        val position = Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) {
                return@use null
            }
            symbols.readInt64(call, iter)
        } ?: return
        onDispatch {
            val id = knownIdForOwner(sender) ?: return@onDispatch
            val merged = synchronized(known) {
                val previous = known[id] ?: return@synchronized null
                val updated = previous.copy(positionMicros = position)
                known[id] = updated
                merges[id] = (merges[id] ?: 0L) + 1
                updated
            } ?: return@onDispatch
            deliver(PlayerEvent.Changed(merged))
        }
    }

    /** Hand work to the dispatch thread, or drop it where the reader is closing. */
    private fun onDispatch(body: () -> Unit) {
        runCatching { dispatch.execute(body) }
            .onFailure { log.debug("signal dropped, the reader is closing") }
    }

    /**
     * Map a unique sender back to the well-known name we know it by.
     *
     * Signals carry `:1.42`, never the readable name, so without this every
     * change from every player looks like it came from nobody. Resolved by
     * asking the bus, and cached in [owners] because it does not change while a
     * player lives.
     */
    private fun knownIdForOwner(sender: String): String? {
        owners[sender]?.let { return it }
        val resolved = synchronized(known) { known.keys.toList() }
            .firstOrNull { nameOwner(it) == sender }
        if (resolved != null) owners[sender] = resolved
        return resolved
    }

    // -- reads -----------------------------------------------------------------

    private fun listNames(): List<String> = Arena.ofConfined().use { call ->
        val message = bus.newCall(call, DBUS_SERVICE, DBUS_PATH, DBUS_SERVICE, "ListNames")
            ?: return emptyList()
        val reply = bus.call(message) ?: return emptyList()
        try {
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(reply, iter) as Int) == 0) {
                return emptyList()
            }
            symbols.readStringArray(call, iter)
        } finally {
            runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        }
    }

    private fun nameOwner(name: String): String? = Arena.ofConfined().use { call ->
        val message = bus.newCall(call, DBUS_SERVICE, DBUS_PATH, DBUS_SERVICE, "GetNameOwner") ?: return null
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, name)
        val reply = bus.call(message) ?: return null
        try {
            val replyIter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(reply, replyIter) as Int) == 0) return null
            symbols.readString(call, replyIter)
        } finally {
            runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        }
    }

    /** Both interfaces of one player, or null when it went away mid-read. */
    private fun read(name: String): ForeignPlayer? {
        val player = getAll(name, Mpris.PLAYER_INTERFACE) ?: return null
        // Recorded while off the bus thread, where a round trip is affordable.
        // Every signal from this player afterwards resolves out of the map.
        nameOwner(name)?.let { owners[it] = name }
        val root = getAll(name, Mpris.ROOT_INTERFACE).orEmpty()
        return ForeignPlayer(
            id = name,
            // Falls back to the bus suffix: a player without an Identity is
            // still a player, and an unnamed row is worse than an ugly one.
            identity = (root[Mpris.PROP_IDENTITY] as? String)?.takeIf { it.isNotBlank() }
                ?: name.removePrefix(Mpris.BUS_NAME_PREFIX),
            playback = Mpris.stateOf(player[Mpris.PROP_PLAYBACK_STATUS] as? String),
            metadata = metadataOf(player[Mpris.PROP_METADATA]),
            positionMicros = player[Mpris.PROP_POSITION] as? Long ?: 0L,
            canControl = player[Mpris.PROP_CAN_CONTROL] as? Boolean ?: false,
            canGoNext = player[Mpris.PROP_CAN_GO_NEXT] as? Boolean ?: false,
            canGoPrevious = player[Mpris.PROP_CAN_GO_PREVIOUS] as? Boolean ?: false,
            // Null rather than a default, because these are optional in the
            // specification: a widget reading a missing property as false would
            // draw a shuffle button for a player that never offered one.
            loop = Mpris.modeOf(player[Mpris.PROP_LOOP_STATUS] as? String),
            shuffle = player[Mpris.PROP_SHUFFLE] as? Boolean,
            fullscreen = root[Mpris.PROP_FULLSCREEN] as? Boolean,
            canRaise = root[Mpris.PROP_CAN_RAISE] as? Boolean ?: false,
            canQuit = root[Mpris.PROP_CAN_QUIT] as? Boolean ?: false,
            canSetFullscreen = root[Mpris.PROP_CAN_SET_FULLSCREEN] as? Boolean ?: false,
        )
    }

    private fun getAll(name: String, iface: String): Map<String, Any?>? = Arena.ofConfined().use { call ->
        val message = bus.newCall(call, name, Mpris.OBJECT_PATH, Mpris.PROPERTIES_INTERFACE, "GetAll")
            ?: return null
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, iface)
        val reply = bus.call(message) ?: return null
        try {
            val replyIter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(reply, replyIter) as Int) == 0) {
                return null
            }
            readVariantDict(call, replyIter)
        } finally {
            runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        }
    }

    /**
     * An `a{sv}` into a map, with each variant read by whatever it turned out
     * to hold.
     *
     * Players disagree about types for the same key -- `mpris:length` shows up
     * as both int64 and uint64 in the wild -- so the reader follows the wire
     * rather than the specification, and a key whose type it does not recognise
     * is dropped instead of guessed at.
     */
    private fun readVariantDict(call: Arena, iter: MemorySegment): Map<String, Any?> {
        if (symbols.argType(iter) != DBusAbi.TYPE_ARRAY) return emptyMap()
        val array = symbols.recurseOrNull(call, iter) ?: return emptyMap()
        val values = mutableMapOf<String, Any?>()
        while (symbols.argType(array) != DBusAbi.TYPE_INVALID) {
            // Every recursion here is into something a peer wrote. An `as`
            // where an `a{sv}` was expected leaves the cursor on a string, and
            // recursing into one aborts the process from inside libdbus, so a
            // shape that is not the one this reads stops the walk instead.
            val entry = symbols.recurseOrNull(call, array) ?: break
            val key = symbols.readString(call, entry)
            symbols.next(entry)
            val variant = symbols.recurseOrNull(call, entry) ?: break
            if (key != null) {
                when (symbols.argType(variant)) {
                    DBusAbi.TYPE_STRING, DBusAbi.TYPE_OBJECT_PATH ->
                        values[key] = symbols.readString(call, variant)
                    DBusAbi.TYPE_INT64, DBusAbi.TYPE_UINT64 ->
                        values[key] = symbols.readInt64(call, variant)
                    DBusAbi.TYPE_DOUBLE -> values[key] = symbols.readDouble(call, variant)
                    DBusAbi.TYPE_BOOLEAN -> values[key] = symbols.readBoolean(call, variant)
                    DBusAbi.TYPE_INT32, DBusAbi.TYPE_UINT32 -> values[key] = symbols.readInt32(call, variant)
                    // An array is either `as` or `a{sv}` -- artists or the
                    // metadata map -- and the variant's own type does not say
                    // which. Reading both as string arrays turned every track's
                    // metadata into an empty list, silently, because an `a{sv}`
                    // read that way yields nothing rather than failing.
                    DBusAbi.TYPE_ARRAY -> {
                        val element = symbols.recurse(call, variant)
                        values[key] = if (symbols.argType(element) == DBusAbi.TYPE_DICT_ENTRY) {
                            readVariantDict(call, variant)
                        } else {
                            symbols.readStringArray(call, variant)
                        }
                    }
                    DBusAbi.TYPE_VARIANT -> values[key] = null
                    else -> Unit
                }
            }
            symbols.next(array)
        }
        return values
    }

    @Suppress("UNCHECKED_CAST")
    private fun metadataOf(raw: Any?): TrackMetadata {
        val map = raw as? Map<String, Any?> ?: return TrackMetadata.EMPTY
        return TrackMetadata(
            title = map[Mpris.KEY_TITLE] as? String,
            artists = (map[Mpris.KEY_ARTIST] as? List<String>).orEmpty(),
            album = map[Mpris.KEY_ALBUM] as? String,
            albumArtists = (map[Mpris.KEY_ALBUM_ARTIST] as? List<String>).orEmpty(),
            durationMicros = map[Mpris.KEY_LENGTH] as? Long,
            trackNumber = map[Mpris.KEY_TRACK_NUMBER] as? Int,
            artUrl = map[Mpris.KEY_ART_URL] as? String,
            trackId = map[Mpris.KEY_TRACK_ID] as? String,
        )
    }

    private fun merge(
        previous: ForeignPlayer,
        iface: String?,
        changed: Map<String, Any?>,
        invalidated: List<String>,
    ): ForeignPlayer = when (iface) {
        Mpris.ROOT_INTERFACE -> previous.copy(
            // A player without an Identity keeps the name it was listed under,
            // the same fallback the first read makes, because a row that went
            // blank is worse than one that never changed.
            identity = (changed[Mpris.PROP_IDENTITY] as? String)?.takeIf { it.isNotBlank() }
                ?: previous.identity,
            fullscreen = optional(previous.fullscreen, Mpris.PROP_FULLSCREEN, changed, invalidated),
            // The three flags say what the player will accept, and a player is
            // free to change its mind while it runs. Invalidated means the
            // property is gone, and a capability nobody advertises is one a
            // widget must not draw.
            canRaise = flag(previous.canRaise, Mpris.PROP_CAN_RAISE, changed, invalidated),
            canQuit = flag(previous.canQuit, Mpris.PROP_CAN_QUIT, changed, invalidated),
            canSetFullscreen = flag(
                previous.canSetFullscreen, Mpris.PROP_CAN_SET_FULLSCREEN, changed, invalidated,
            ),
        )
        else -> previous.copy(
            playback = changed[Mpris.PROP_PLAYBACK_STATUS]?.let { Mpris.stateOf(it as? String) }
                ?: previous.playback,
            metadata = if (Mpris.PROP_METADATA in changed) {
                metadataOf(changed[Mpris.PROP_METADATA])
            } else {
                previous.metadata
            },
            canControl = changed[Mpris.PROP_CAN_CONTROL] as? Boolean ?: previous.canControl,
            canGoNext = changed[Mpris.PROP_CAN_GO_NEXT] as? Boolean ?: previous.canGoNext,
            canGoPrevious = changed[Mpris.PROP_CAN_GO_PREVIOUS] as? Boolean ?: previous.canGoPrevious,
            // Mentioned in the signal means the answer comes from the signal,
            // including when it is a mode this library does not model: reading
            // that as "unchanged" leaves players() and this disagreeing about
            // the same player, and a widget's repeat button appearing or
            // vanishing depending on which of the two last spoke.
            loop = when {
                Mpris.PROP_LOOP_STATUS in invalidated -> null
                Mpris.PROP_LOOP_STATUS in changed ->
                    Mpris.modeOf(changed[Mpris.PROP_LOOP_STATUS] as? String)
                else -> previous.loop
            },
            shuffle = optional(previous.shuffle, Mpris.PROP_SHUFFLE, changed, invalidated),
        )
    }

    /**
     * What an optional boolean is now: unchanged where the signal did not
     * mention it, and gone where the player said it no longer carries it.
     */
    private fun optional(
        previous: Boolean?,
        key: String,
        changed: Map<String, Any?>,
        invalidated: List<String>,
    ): Boolean? = when {
        key in invalidated -> null
        key in changed -> changed[key] as? Boolean
        else -> previous
    }

    /**
     * The same, for a flag that says what the player will accept.
     *
     * Absent reads as false rather than as null: these are answers about
     * whether a control may be drawn, and the specification's own default for
     * one a player does not publish is that it may not.
     */
    private fun flag(
        previous: Boolean,
        key: String,
        changed: Map<String, Any?>,
        invalidated: List<String>,
    ): Boolean = when {
        key in invalidated -> false
        key in changed -> changed[key] as? Boolean ?: false
        else -> previous
    }

    /**
     * Set one property on somebody else's player, on the interface that carries
     * it: Volume, LoopStatus and Shuffle belong to the player and Fullscreen to
     * the root.
     *
     * False covers both a player that is gone and one that refused, because
     * both come back as no reply. A refusal is the ordinary answer for an
     * optional property the target does not carry, which is what
     * [ForeignPlayer.loop] and its neighbours are read for beforehand.
     */
    private fun setProperty(
        call: Arena,
        playerId: String,
        iface: String,
        property: String,
        value: (MemorySegment) -> Unit,
    ): Boolean {
        val message = bus.newCall(call, playerId, Mpris.OBJECT_PATH, Mpris.PROPERTIES_INTERFACE, "Set")
            ?: return false
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, iface)
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, property)
        value(iter)
        val reply = bus.call(message) ?: return false
        runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        return true
    }

    private fun readMessageString(accessor: String, message: MemorySegment): String? =
        (symbols.handle(accessor).invokeExact(message) as MemorySegment).readCString()

    private fun emit(event: PlayerEvent) {
        onDispatch { deliver(event) }
    }

    /** Already on the dispatch thread, which is the only thread that calls this. */
    private fun deliver(event: PlayerEvent) {
        listeners.toList().forEach { listener ->
            runCatching { listener(event) }
                .onFailure { log.warn("player listener threw: {}", it.message) }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mpris")

        private const val DBUS_SERVICE = "org.freedesktop.DBus"
        private const val DBUS_PATH = "/org/freedesktop/DBus"

        /** Open a reader, or null when there is no session bus. */
        fun openOrNull(): SessionReader? {
            val bus = DBusConnection.openOrNull("mpris-read") ?: return null
            val reader = MprisReader(bus)
            bus.onMessage(reader::handle)
            bus.start()
            // Everything a player says about itself, plus the bus telling us
            // when one arrives or leaves.
            bus.addMatch("type='signal',interface='${Mpris.PROPERTIES_INTERFACE}',member='PropertiesChanged'")
            bus.addMatch("type='signal',interface='${Mpris.PLAYER_INTERFACE}',member='Seeked'")
            bus.addMatch(
                "type='signal',sender='$DBUS_SERVICE',interface='$DBUS_SERVICE',member='NameOwnerChanged'",
            )
            return reader
        }
    }
}
