package dev.hivens.libsound.session.mpris

import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.ForeignPlayer
import dev.hivens.libsound.PlayerEvent
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionReader
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.dbus.DBusAbi
import dev.hivens.libsound.session.dbus.DBusConnection
import dev.hivens.libsound.session.dbus.allocateUtf8
import dev.hivens.libsound.session.dbus.appendInt64
import dev.hivens.libsound.session.dbus.appendString
import dev.hivens.libsound.session.dbus.appendVariantDouble
import dev.hivens.libsound.session.dbus.argType
import dev.hivens.libsound.session.dbus.next
import dev.hivens.libsound.session.dbus.readCString
import dev.hivens.libsound.session.dbus.readDouble
import dev.hivens.libsound.session.dbus.readInt64
import dev.hivens.libsound.session.dbus.readString
import dev.hivens.libsound.session.dbus.readStringArray
import dev.hivens.libsound.session.dbus.recurse
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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
        return listNames()
            .filter { it.startsWith(Mpris.BUS_NAME_PREFIX) }
            .mapNotNull { read(it) }
            .also { fresh -> synchronized(known) { fresh.forEach { known[it.id] = it } } }
    }

    override fun control(playerId: String, command: SessionCommand): Boolean {
        if (closed.get()) return false
        return Arena.ofConfined().use { call ->
            val (member, argument) = when (command) {
                SessionCommand.Play -> "Play" to null
                SessionCommand.Pause -> "Pause" to null
                SessionCommand.PlayPause -> "PlayPause" to null
                SessionCommand.Stop -> "Stop" to null
                SessionCommand.Next -> "Next" to null
                SessionCommand.Previous -> "Previous" to null
                is SessionCommand.Seek -> "Seek" to command.offsetMicros
                is SessionCommand.SetPosition -> "SetPosition" to command.positionMicros
                // Volume is a property rather than a method, so it goes through
                // Properties.Set like any other.
                is SessionCommand.SetVolume -> return@use setVolume(call, playerId, command.volume)
            }
            val message = bus.newCall(call, playerId, Mpris.OBJECT_PATH, Mpris.PLAYER_INTERFACE, member)
                ?: return@use false
            if (argument != null) {
                val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
                symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
                if (command is SessionCommand.SetPosition) {
                    // SetPosition takes the track id first, and the player drops
                    // the command when it names a track that is no longer
                    // current -- which is the whole reason it is carried.
                    symbols.appendString(
                        call, iter, DBusAbi.TYPE_OBJECT_PATH, Mpris.trackPath(command.trackId),
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
        dispatch.shutdownNow()
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
                synchronized(known) { known.remove(name) }
                emit(PlayerEvent.Gone(name))
                return
            }
            if (oldOwner.isNullOrEmpty()) {
                // A fresh player. Reading it here would block the bus thread on
                // a round trip to somebody who has only just arrived, so the
                // read happens on the dispatch thread with everything else.
                dispatch.execute {
                    read(name)?.let { player ->
                        synchronized(known) { known[name] = player }
                        emit(PlayerEvent.Appeared(player))
                    }
                }
            }
        }
    }

    private fun onPropertiesChanged(message: MemorySegment) {
        val sender = readMessageString("dbus_message_get_sender", message) ?: return
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return
            if (symbols.readString(call, iter) != Mpris.PLAYER_INTERFACE) return
            symbols.next(iter)
            val changed = readVariantDict(call, iter)
            if (changed.isEmpty()) return

            // The signal comes from a unique name and the map is keyed by the
            // well-known one, so there is no cheaper branch to try first: a
            // sender can never equal a key here.
            val id = knownIdForOwner(sender) ?: return
            val merged = synchronized(known) {
                val previous = known[id] ?: return@synchronized null
                val updated = merge(previous, changed)
                known[id] = updated
                updated
            } ?: return
            emit(PlayerEvent.Changed(merged))
        }
    }

    private fun onSeeked(message: MemorySegment) {
        val sender = readMessageString("dbus_message_get_sender", message) ?: return
        Arena.ofConfined().use { call ->
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((symbols.handle("dbus_message_iter_init").invokeExact(message, iter) as Int) == 0) return
            val position = symbols.readInt64(call, iter) ?: return
            val id = knownIdForOwner(sender) ?: return
            val merged = synchronized(known) {
                val previous = known[id] ?: return@synchronized null
                val updated = previous.copy(positionMicros = position)
                known[id] = updated
                updated
            } ?: return
            emit(PlayerEvent.Changed(merged))
        }
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
        val root = getAll(name, Mpris.ROOT_INTERFACE).orEmpty()
        return ForeignPlayer(
            id = name,
            // Falls back to the bus suffix: a player without an Identity is
            // still a player, and an unnamed row is worse than an ugly one.
            identity = (root["Identity"] as? String)?.takeIf { it.isNotBlank() }
                ?: name.removePrefix(Mpris.BUS_NAME_PREFIX),
            playback = Mpris.stateOf(player["PlaybackStatus"] as? String),
            metadata = metadataOf(player["Metadata"]),
            positionMicros = player["Position"] as? Long ?: 0L,
            canControl = player["CanControl"] as? Boolean ?: false,
            canGoNext = player["CanGoNext"] as? Boolean ?: false,
            canGoPrevious = player["CanGoPrevious"] as? Boolean ?: false,
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
        val array = symbols.recurse(call, iter)
        val values = mutableMapOf<String, Any?>()
        while (symbols.argType(array) != DBusAbi.TYPE_INVALID) {
            val entry = symbols.recurse(call, array)
            val key = symbols.readString(call, entry)
            symbols.next(entry)
            val variant = symbols.recurse(call, entry)
            if (key != null) {
                when (symbols.argType(variant)) {
                    DBusAbi.TYPE_STRING, DBusAbi.TYPE_OBJECT_PATH ->
                        values[key] = symbols.readString(call, variant)
                    DBusAbi.TYPE_INT64, DBusAbi.TYPE_UINT64 ->
                        values[key] = symbols.readInt64(call, variant)
                    DBusAbi.TYPE_DOUBLE -> values[key] = symbols.readDouble(call, variant)
                    DBusAbi.TYPE_BOOLEAN -> values[key] = readBoolean(call, variant)
                    DBusAbi.TYPE_INT32, DBusAbi.TYPE_UINT32 -> values[key] = readInt32(call, variant)
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

    private fun readBoolean(call: Arena, iter: MemorySegment): Boolean {
        val out = call.allocate(ValueLayout.JAVA_INT)
        symbols.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        return out.get(ValueLayout.JAVA_INT, 0) != 0
    }

    private fun readInt32(call: Arena, iter: MemorySegment): Int {
        val out = call.allocate(ValueLayout.JAVA_INT)
        symbols.handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
        return out.get(ValueLayout.JAVA_INT, 0)
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

    private fun merge(previous: ForeignPlayer, changed: Map<String, Any?>): ForeignPlayer = previous.copy(
        playback = changed["PlaybackStatus"]?.let { Mpris.stateOf(it as? String) } ?: previous.playback,
        metadata = if ("Metadata" in changed) metadataOf(changed["Metadata"]) else previous.metadata,
        canControl = changed["CanControl"] as? Boolean ?: previous.canControl,
        canGoNext = changed["CanGoNext"] as? Boolean ?: previous.canGoNext,
        canGoPrevious = changed["CanGoPrevious"] as? Boolean ?: previous.canGoPrevious,
    )

    private fun setVolume(call: Arena, playerId: String, volume: Double): Boolean {
        val message = bus.newCall(call, playerId, Mpris.OBJECT_PATH, Mpris.PROPERTIES_INTERFACE, "Set")
            ?: return false
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, Mpris.PLAYER_INTERFACE)
        symbols.appendString(call, iter, DBusAbi.TYPE_STRING, Mpris.PROP_VOLUME)
        symbols.appendVariantDouble(call, iter, volume)
        val reply = bus.call(message) ?: return false
        runCatching { symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        return true
    }

    private fun readMessageString(accessor: String, message: MemorySegment): String? =
        (symbols.handle(accessor).invokeExact(message) as MemorySegment).readCString()

    private fun emit(event: PlayerEvent) {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            dispatch.execute {
                snapshot.forEach { listener ->
                    runCatching { listener(event) }
                        .onFailure { log.warn("player listener threw: {}", it.message) }
                }
            }
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
