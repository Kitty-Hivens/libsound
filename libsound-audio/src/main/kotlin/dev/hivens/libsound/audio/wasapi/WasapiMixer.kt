package dev.hivens.libsound.audio.wasapi

import dev.hivens.libsound.AudioMixer
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Everyone else's audio on Windows, through `IAudioSessionManager2` -- which is
 * what the system volume mixer is itself built on, so what this lists is what a
 * user already sees in `sndvol`.
 *
 * ## What it puts back
 *
 * Windows remembers per-application volume across runs of that application, so
 * this is the one surface here that writes state outliving its process. Volume
 * and mute are recorded separately against the values they replaced, and [close]
 * restores whatever is still outstanding -- the same obligation, for the same
 * reason, as the libpulse mixer.
 *
 * ## What Windows will not do
 *
 * There is no public way to move one session to another device: routing is a
 * per-application setting the user makes in Settings, not something an
 * application may do to another. So [Capability.STREAM_ROUTING] is absent and
 * [moveTo] answers false rather than pretending, which is the whole point of
 * asking a capability before drawing a device menu on a mixer row.
 */
internal class WasapiMixer private constructor(
    private val com: WasapiCom,
    private val enumerator: MemorySegment,
) : AudioMixer {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    private val closed = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /** Kept apart on purpose; see the libpulse mixer for why one snapshot is wrong. */
    private val originalVolumes = ConcurrentHashMap<String, Float>()
    private val originalMutes = ConcurrentHashMap<String, Boolean>()

    /**
     * One walk of the session list at a time.
     *
     * Each walk takes and releases a chain of COM interfaces, and two walks
     * interleaving would release each other's. The event thread enumerates on
     * every session notification while a consumer may be enumerating too, so
     * this is contended rather than theoretical.
     */
    private val roundTrip = ReentrantLock()

    /**
     * Sessions we hold a reference to, so their event objects stay registered.
     *
     * The Windows volume mixer holds the same references for the same reason: a
     * session control that is released stops reporting, and a mixer that only
     * learned about changes it made itself would show stale rows.
     */
    private val watched = ConcurrentHashMap<String, Watch>()

    private val nextToken = AtomicLong(1)

    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-wasapi-mixer-events").apply { isDaemon = true }
    }

    private lateinit var sessionEventsVtable: MemorySegment
    private lateinit var sessionNotification: MemorySegment

    /** The manager the notification is registered on; released last. */
    private var registeredManager: MemorySegment = MemorySegment.NULL

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_ENUMERATION,
        Capability.STREAM_CONTROL,
    )

    override val isOpen: Boolean get() = !closed.get()

    override fun streams(): List<AudioStream> {
        if (closed.get()) return emptyList()
        com.ensureComOnThisThread()
        return roundTrip.withLock { runCatching { walkSessions() }.getOrElse { emptyList() } }
    }

    override fun setVolume(id: StreamId, volume: Float): Boolean {
        if (closed.get()) return false
        com.ensureComOnThisThread()
        val clamped = volume.coerceIn(0f, 1f)
        return roundTrip.withLock {
            rememberVolume(id.value)
            withVolumeOf(id.value) { simpleVolume ->
                // The event context is our own guid so that our own change comes
                // back through OnSimpleVolumeChanged marked as ours; passing null
                // would make every change look like it came from elsewhere.
                Arena.ofConfined().use { call ->
                    com.method(
                        simpleVolume, WasapiAbi.VOLUME_SET_MASTER,
                        FunctionDescriptor.of(I32, ADDR, ValueLayout.JAVA_FLOAT, ADDR),
                    ).invokeExact(simpleVolume, clamped, WasapiCom.guid(call, EVENT_CONTEXT)) as Int == WasapiAbi.S_OK
                }
            }
        }
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        if (closed.get()) return false
        com.ensureComOnThisThread()
        return roundTrip.withLock {
            rememberMute(id.value)
            withVolumeOf(id.value) { simpleVolume ->
                Arena.ofConfined().use { call ->
                    com.method(
                        simpleVolume, WasapiAbi.VOLUME_SET_MUTE,
                        FunctionDescriptor.of(I32, ADDR, I32, ADDR),
                    ).invokeExact(
                        simpleVolume, if (muted) 1 else 0, WasapiCom.guid(call, EVENT_CONTEXT),
                    ) as Int == WasapiAbi.S_OK
                }
            }
        }
    }

    /**
     * Always false. Windows exposes no way to move another application's session
     * to another device -- routing is the user's setting, not ours to change --
     * and [Capability.STREAM_ROUTING] says so before a caller reaches here.
     */
    override fun moveTo(id: StreamId, device: DeviceId): Boolean = false

    override fun restoreAll() {
        com.ensureComOnThisThread()
        val volumes = originalVolumes.entries.map { it.key to it.value }
        volumes.forEach { originalVolumes.remove(it.first) }
        val mutes = originalMutes.entries.map { it.key to it.value }
        mutes.forEach { originalMutes.remove(it.first) }
        roundTrip.withLock {
            volumes.forEach { (id, volume) ->
                runCatching {
                    withVolumeOf(id) { simpleVolume ->
                        Arena.ofConfined().use { call ->
                            com.method(
                                simpleVolume, WasapiAbi.VOLUME_SET_MASTER,
                                FunctionDescriptor.of(I32, ADDR, ValueLayout.JAVA_FLOAT, ADDR),
                            ).invokeExact(
                                simpleVolume, volume, WasapiCom.guid(call, EVENT_CONTEXT),
                            ) as Int == WasapiAbi.S_OK
                        }
                    }
                }.onFailure { log.debug("could not restore volume of {}: {}", id, it.message) }
            }
            mutes.forEach { (id, muted) ->
                runCatching {
                    withVolumeOf(id) { simpleVolume ->
                        Arena.ofConfined().use { call ->
                            com.method(
                                simpleVolume, WasapiAbi.VOLUME_SET_MUTE,
                                FunctionDescriptor.of(I32, ADDR, I32, ADDR),
                            ).invokeExact(
                                simpleVolume, if (muted) 1 else 0, WasapiCom.guid(call, EVENT_CONTEXT),
                            ) as Int == WasapiAbi.S_OK
                        }
                    }
                }.onFailure { log.debug("could not restore mute of {}: {}", id, it.message) }
            }
        }
    }

    override fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { restoreAll() }
        runCatching { unregisterAll() }
        // Under the walk lock. com.close() frees the arena holding the vtables
        // the audio service calls through, and a walk in flight is holding
        // interfaces from it.
        roundTrip.withLock {
            com.release(enumerator)
            com.close()
        }
    }

    // -- enumeration ----------------------------------------------------------

    /**
     * Walk every session on the default render endpoint.
     *
     * Every interface taken here is released here. COM has no scope and no
     * finaliser worth relying on, so a walk that returns early past a Release is
     * a leak on a path a mixer polls.
     */
    private fun walkSessions(): List<AudioStream> {
        val device = defaultRenderDevice() ?: return emptyList()
        try {
            val manager = activateSessionManager(device) ?: return emptyList()
            try {
                val sessions = sessionEnumerator(manager) ?: return emptyList()
                try {
                    return readSessions(sessions)
                } finally {
                    com.release(sessions)
                }
            } finally {
                com.release(manager)
            }
        } finally {
            com.release(device)
        }
    }

    private fun readSessions(sessions: MemorySegment): List<AudioStream> = Arena.ofConfined().use { call ->
        val countOut = call.allocate(I32)
        val counted = com.method(
            sessions, WasapiAbi.SESSION_ENUM_GET_COUNT, FunctionDescriptor.of(I32, ADDR, ADDR),
        ).invokeExact(sessions, countOut) as Int
        if (counted != WasapiAbi.S_OK) return emptyList()

        val out = ArrayList<AudioStream>(countOut.get(I32, 0).coerceAtLeast(0))
        for (index in 0 until countOut.get(I32, 0)) {
            val controlOut = call.allocate(ADDR)
            val got = com.method(
                sessions, WasapiAbi.SESSION_ENUM_GET_SESSION,
                FunctionDescriptor.of(I32, ADDR, I32, ADDR),
            ).invokeExact(sessions, index, controlOut) as Int
            if (got != WasapiAbi.S_OK) continue
            val control = controlOut.get(ADDR, 0)
            if (control.address() == 0L) continue
            // Not released here when it becomes a watch: the event registration
            // is what keeps the session reporting, and it needs the reference.
            val stream = runCatching { readSession(control) }.getOrNull()
            if (stream == null) com.release(control)
            stream?.let(out::add)
        }
        return out
    }

    private fun readSession(control: MemorySegment): AudioStream? = Arena.ofConfined().use { call ->
        val control2 = com.queryInterface(control, WasapiAbi.IID_AUDIO_SESSION_CONTROL2) ?: return null
        try {
            val id = wideOut(call) { out ->
                com.method(
                    control2, WasapiAbi.SESSION_CONTROL2_GET_SESSION_INSTANCE_IDENTIFIER,
                    FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(control2, out) as Int
            }?.ifBlank { null } ?: return null

            val processId = call.allocate(I32).let { out ->
                val hr = com.method(
                    control2, WasapiAbi.SESSION_CONTROL2_GET_PROCESS_ID,
                    FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(control2, out) as Int
                if (hr == WasapiAbi.S_OK) out.get(I32, 0) else 0
            }
            val systemSounds = com.method(
                control2, WasapiAbi.SESSION_CONTROL2_IS_SYSTEM_SOUNDS,
                FunctionDescriptor.of(I32, ADDR),
            ).invokeExact(control2) as Int == WasapiAbi.S_OK

            val displayName = wideOut(call) { out ->
                com.method(
                    control, WasapiAbi.SESSION_CONTROL_GET_DISPLAY_NAME,
                    FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(control, out) as Int
            }
            val iconPath = wideOut(call) { out ->
                com.method(
                    control, WasapiAbi.SESSION_CONTROL_GET_ICON_PATH,
                    FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(control, out) as Int
            }
            val state = call.allocate(I32).let { out ->
                val hr = com.method(
                    control, WasapiAbi.SESSION_CONTROL_GET_STATE, FunctionDescriptor.of(I32, ADDR, ADDR),
                ).invokeExact(control, out) as Int
                if (hr == WasapiAbi.S_OK) out.get(I32, 0) else WasapiAbi.SESSION_STATE_ACTIVE
            }

            var volume = 1f
            var muted = false
            com.queryInterface(control, WasapiAbi.IID_SIMPLE_AUDIO_VOLUME)?.let { simple ->
                try {
                    val volumeOut = call.allocate(ValueLayout.JAVA_FLOAT)
                    if (com.method(
                            simple, WasapiAbi.VOLUME_GET_MASTER, FunctionDescriptor.of(I32, ADDR, ADDR),
                        ).invokeExact(simple, volumeOut) as Int == WasapiAbi.S_OK
                    ) {
                        volume = volumeOut.get(ValueLayout.JAVA_FLOAT, 0).coerceIn(0f, 1f)
                    }
                    val muteOut = call.allocate(I32)
                    if (com.method(
                            simple, WasapiAbi.VOLUME_GET_MUTE, FunctionDescriptor.of(I32, ADDR, ADDR),
                        ).invokeExact(simple, muteOut) as Int == WasapiAbi.S_OK
                    ) {
                        muted = muteOut.get(I32, 0) != 0
                    }
                } finally {
                    com.release(simple)
                }
            }

            val stream = AudioStream(
                id = StreamId(id),
                applicationName = when {
                    systemSounds -> SYSTEM_SOUNDS_NAME
                    !displayName.isNullOrBlank() -> displayName
                    else -> processName(processId)
                },
                iconName = iconPath?.ifBlank { null },
                device = null,
                volume = volume,
                muted = muted,
                active = state == WasapiAbi.SESSION_STATE_ACTIVE,
                isOurs = processId.toLong() == OUR_PID,
            )
            // Last, and after nothing that can throw: from here the control
            // belongs to the watch, and releasing it again in the caller's
            // failure path would be a double release COM does not survive.
            watch(id, control)
            return stream
        } finally {
            com.release(control2)
        }
    }

    /**
     * Find one session again by its instance identifier and hand its
     * `ISimpleAudioVolume` to [body].
     *
     * Re-walking rather than caching the volume interface: a session that goes
     * away leaves a stale pointer, and setting volume through one is the kind of
     * use-after-free COM does not report.
     */
    private inline fun withVolumeOf(id: String, body: (MemorySegment) -> Boolean): Boolean {
        val device = defaultRenderDevice() ?: return false
        try {
            val manager = activateSessionManager(device) ?: return false
            try {
                val sessions = sessionEnumerator(manager) ?: return false
                try {
                    return forSession(sessions, id, body)
                } finally {
                    com.release(sessions)
                }
            } finally {
                com.release(manager)
            }
        } finally {
            com.release(device)
        }
    }

    private inline fun forSession(
        sessions: MemorySegment,
        id: String,
        body: (MemorySegment) -> Boolean,
    ): Boolean = Arena.ofConfined().use { call ->
        val countOut = call.allocate(I32)
        if (com.method(
                sessions, WasapiAbi.SESSION_ENUM_GET_COUNT, FunctionDescriptor.of(I32, ADDR, ADDR),
            ).invokeExact(sessions, countOut) as Int != WasapiAbi.S_OK
        ) {
            return false
        }
        for (index in 0 until countOut.get(I32, 0)) {
            val controlOut = call.allocate(ADDR)
            if (com.method(
                    sessions, WasapiAbi.SESSION_ENUM_GET_SESSION,
                    FunctionDescriptor.of(I32, ADDR, I32, ADDR),
                ).invokeExact(sessions, index, controlOut) as Int != WasapiAbi.S_OK
            ) {
                continue
            }
            val control = controlOut.get(ADDR, 0)
            if (control.address() == 0L) continue
            try {
                val control2 = com.queryInterface(control, WasapiAbi.IID_AUDIO_SESSION_CONTROL2) ?: continue
                val found = try {
                    wideOut(call) { out ->
                        com.method(
                            control2, WasapiAbi.SESSION_CONTROL2_GET_SESSION_INSTANCE_IDENTIFIER,
                            FunctionDescriptor.of(I32, ADDR, ADDR),
                        ).invokeExact(control2, out) as Int
                    } == id
                } finally {
                    com.release(control2)
                }
                if (!found) continue
                val simple = com.queryInterface(control, WasapiAbi.IID_SIMPLE_AUDIO_VOLUME) ?: return false
                try {
                    return body(simple)
                } finally {
                    com.release(simple)
                }
            } finally {
                com.release(control)
            }
        }
        return false
    }

    private fun rememberVolume(id: String) {
        if (originalVolumes.containsKey(id)) return
        streams().firstOrNull { it.id.value == id }?.let { originalVolumes.putIfAbsent(id, it.volume) }
    }

    private fun rememberMute(id: String) {
        if (originalMutes.containsKey(id)) return
        streams().firstOrNull { it.id.value == id }?.let { originalMutes.putIfAbsent(id, it.muted) }
    }

    // -- the COM chain ---------------------------------------------------------

    private fun defaultRenderDevice(): MemorySegment? = Arena.ofConfined().use { call ->
        val out = call.allocate(ADDR)
        val hr = com.method(
            enumerator, WasapiAbi.GET_DEFAULT_AUDIO_ENDPOINT,
            FunctionDescriptor.of(I32, ADDR, I32, I32, ADDR),
        ).invokeExact(enumerator, WasapiAbi.E_RENDER, WasapiAbi.E_CONSOLE, out) as Int
        if (hr != WasapiAbi.S_OK) return null
        out.get(ADDR, 0).takeIf { it.address() != 0L }
    }

    private fun activateSessionManager(device: MemorySegment): MemorySegment? = Arena.ofConfined().use { call ->
        val out = call.allocate(ADDR)
        val hr = com.method(
            device, WasapiAbi.DEVICE_ACTIVATE,
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, ADDR),
        ).invokeExact(
            device, WasapiCom.guid(call, WasapiAbi.IID_AUDIO_SESSION_MANAGER2),
            WasapiAbi.CLSCTX_ALL, MemorySegment.NULL, out,
        ) as Int
        if (hr != WasapiAbi.S_OK) return null
        out.get(ADDR, 0).takeIf { it.address() != 0L }
    }

    private fun sessionEnumerator(manager: MemorySegment): MemorySegment? = Arena.ofConfined().use { call ->
        val out = call.allocate(ADDR)
        val hr = com.method(
            manager, WasapiAbi.SESSION_MANAGER_GET_SESSION_ENUMERATOR,
            FunctionDescriptor.of(I32, ADDR, ADDR),
        ).invokeExact(manager, out) as Int
        if (hr != WasapiAbi.S_OK) return null
        out.get(ADDR, 0).takeIf { it.address() != 0L }
    }

    /** Read an out-parameter `LPWSTR` and free the shell's copy of it. */
    private inline fun wideOut(call: Arena, get: (MemorySegment) -> Int): String? {
        val out = call.allocate(ADDR)
        if (get(out) != WasapiAbi.S_OK) return null
        val pointer = out.get(ADDR, 0)
        return try {
            WasapiCom.readWide(pointer)
        } finally {
            com.coTaskMemFree(pointer)
        }
    }

    // -- events ---------------------------------------------------------------

    /** One session we hold open so its events keep arriving. */
    private class Watch(val control: MemorySegment, val events: MemorySegment, val token: Long)

    private fun watch(id: String, control: MemorySegment) {
        if (watched.containsKey(id)) {
            com.release(control)
            return
        }
        val token = nextToken.getAndIncrement()
        // Sixteen bytes from the shared arena, which cannot be freed piecewise,
        // so a session watched once costs them until the mixer closes. Kept
        // deliberately: freeing it would mean a per-watch arena closed right
        // after unregistering, and unregistering does not promise that a
        // callback already dispatched has returned. A few kilobytes over a long
        // session is the cheaper of the two mistakes.
        val instance = com.arena.allocate(INSTANCE_SIZE, 8)
        instance.set(ADDR, 0, sessionEventsVtable)
        instance.set(ValueLayout.JAVA_LONG, ADDR.byteSize(), token)
        tokens[token] = id

        val hr = com.method(
            control, WasapiAbi.SESSION_CONTROL_REGISTER_NOTIFICATION,
            FunctionDescriptor.of(I32, ADDR, ADDR),
        ).invokeExact(control, instance) as Int
        if (hr != WasapiAbi.S_OK) {
            tokens.remove(token)
            com.release(control)
            return
        }
        watched[id] = Watch(control, instance, token)
    }

    /** token -> session id, so one shared vtable serves every watched session. */
    private val tokens = ConcurrentHashMap<Long, String>()

    /**
     * Undo one watch, exactly once.
     *
     * `remove` is the claim: whichever caller it returns the entry to owns the
     * teardown, and every other caller gets null and does nothing. Releasing
     * without claiming first let a disconnect arriving during teardown release
     * the same control the teardown was already releasing.
     *
     * Unregistering comes before releasing, and that order is the whole point.
     * A disconnected session is not a destroyed one -- a device change leaves
     * the object alive -- so a registration left behind outlives the arena that
     * holds its vtable, and the audio service then calls through freed memory.
     */
    private fun unwatch(id: String) {
        val watch = watched.remove(id) ?: return
        tokens.remove(watch.token)
        runCatching {
            com.method(
                watch.control, WasapiAbi.SESSION_CONTROL_UNREGISTER_NOTIFICATION,
                FunctionDescriptor.of(I32, ADDR, ADDR),
            ).invokeExact(watch.control, watch.events) as Int
        }
        com.release(watch.control)
    }

    private fun unregisterAll() {
        watched.keys.toList().forEach { unwatch(it) }
        tokens.clear()

        val manager = registeredManager
        registeredManager = MemorySegment.NULL
        if (manager.address() == 0L) return
        runCatching {
            com.method(
                manager, WasapiAbi.SESSION_MANAGER_UNREGISTER_NOTIFICATION,
                FunctionDescriptor.of(I32, ADDR, ADDR),
            ).invokeExact(manager, sessionNotification) as Int
        }
        com.release(manager)
    }

    // Public rather than internal, for the same reason as everywhere else here:
    // Kotlin mangles an internal name and findVirtual looks up what is written.

    /**
     * Hand back only the interfaces this object actually is.
     *
     * Answering yes to every identifier is the tempting shortcut and it is
     * unsafe: the shell queries a callback object for `IMarshal` and
     * `IAgileObject` when it decides how to deliver across an apartment, and a
     * yes there hands it a vtable with four or ten slots to call a marshaller's
     * methods through. The wrong function runs with the wrong signature, which
     * is the same class of failure as a wrong slot index.
     *
     * The two synthesised objects share one stub, so which one `self` is decides
     * which identifier is truthful for it.
     */
    fun onQueryInterface(self: MemorySegment, iid: MemorySegment, out: MemorySegment): Int = runCatching {
        val wanted = readGuid(iid)
        val isNotification = self.address() == sessionNotification.address()
        val mine = wanted == WasapiAbi.IID_UNKNOWN ||
            wanted == if (isNotification) WasapiAbi.IID_AUDIO_SESSION_NOTIFICATION else WasapiAbi.IID_AUDIO_SESSION_EVENTS
        if (!mine) return E_NOINTERFACE
        out.reinterpret(ADDR.byteSize()).set(ADDR, 0, self)
        WasapiAbi.S_OK
    }.getOrDefault(E_FAIL)

    /** A `GUID` back into the canonical text the ABI table stores. */
    private fun readGuid(iid: MemorySegment): String {
        val g = iid.reinterpret(16)
        val d1 = g.get(ValueLayout.JAVA_INT, 0).toUInt()
        val d2 = g.get(ValueLayout.JAVA_SHORT, 4).toUShort()
        val d3 = g.get(ValueLayout.JAVA_SHORT, 6).toUShort()
        val tail = (0 until 8).joinToString("") { "%02X".format(g.get(ValueLayout.JAVA_BYTE, 8L + it).toInt() and 0xFF) }
        return "%08X-%04X-%04X-%s-%s".format(d1.toInt(), d2.toInt(), d3.toInt(), tail.take(4), tail.drop(4))
    }

    /** The objects live in an arena we close on teardown, after unregistering. */
    fun onAddRef(unusedSelf: MemorySegment): Int = 1

    fun onRelease(unusedSelf: MemorySegment): Int = 1

    fun onSessionCreated(unusedSelf: MemorySegment, unusedControl: MemorySegment): Int {
        // The control handed over here belongs to the caller; taking a reference
        // to it would need an AddRef this object cannot honour on teardown. The
        // next walk picks the session up and watches it properly.
        fire {
            // Sampled before the walk: streams() watches everything it sees, so
            // a filter applied afterwards would match nothing and this event
            // would never fire.
            val before = watched.keys.toSet()
            streams().firstOrNull { it.id.value !in before }?.let(StreamEvent::Appeared)
        }
        return WasapiAbi.S_OK
    }

    fun onSimpleVolumeChanged(
        self: MemorySegment,
        unusedVolume: Float,
        unusedMuted: Int,
        unusedContext: MemorySegment,
    ): Int = changed(self)

    fun onStateChanged(self: MemorySegment, unusedState: Int): Int = changed(self)

    fun onSessionDisconnected(self: MemorySegment, unusedReason: Int): Int {
        val id = idOf(self) ?: return WasapiAbi.S_OK
        unwatch(id)
        fire { StreamEvent.Gone(StreamId(id)) }
        return WasapiAbi.S_OK
    }

    fun onDisplayNameChanged(self: MemorySegment, unusedName: MemorySegment, unusedContext: MemorySegment): Int =
        changed(self)

    fun onIconPathChanged(self: MemorySegment, unusedPath: MemorySegment, unusedContext: MemorySegment): Int =
        changed(self)

    fun onChannelVolumeChanged(
        unusedSelf: MemorySegment,
        unusedCount: Int,
        unusedVolumes: MemorySegment,
        unusedChanged: Int,
        unusedContext: MemorySegment,
    ): Int = WasapiAbi.S_OK

    fun onGroupingParamChanged(
        unusedSelf: MemorySegment,
        unusedGroup: MemorySegment,
        unusedContext: MemorySegment,
    ): Int = WasapiAbi.S_OK

    private fun changed(self: MemorySegment): Int {
        val id = idOf(self) ?: return WasapiAbi.S_OK
        fire { streams().firstOrNull { it.id.value == id }?.let(StreamEvent::Changed) }
        return WasapiAbi.S_OK
    }

    private fun idOf(self: MemorySegment): String? =
        runCatching { tokens[self.reinterpret(INSTANCE_SIZE).get(ValueLayout.JAVA_LONG, ADDR.byteSize())] }.getOrNull()

    /**
     * Hand the work to our own thread.
     *
     * These arrive on a thread the audio service owns, and the natural response
     * -- re-walking the session list -- is a chain of COM calls back into the
     * service. Doing that from inside its own callback is how a mixer deadlocks
     * a system service rather than merely itself.
     */
    private fun fire(build: () -> StreamEvent?) {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            dispatch.execute {
                com.ensureComOnThisThread()
                val event = runCatching { build() }.getOrNull() ?: return@execute
                snapshot.forEach { listener ->
                    runCatching { listener(event) }.onFailure { log.warn("stream listener threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("stream event dropped, dispatcher is shut down") }
    }

    // -- building the synthesised objects --------------------------------------

    private fun installNotifications() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val arena = com.arena

        fun stub(name: String, type: MethodType, descriptor: FunctionDescriptor): MemorySegment =
            linker.upcallStub(lookup.findVirtual(WasapiMixer::class.java, name, type).bindTo(this), descriptor, arena)

        val unknown = MethodType.methodType(
            Int::class.javaPrimitiveType, MemorySegment::class.java,
            MemorySegment::class.java, MemorySegment::class.java,
        )
        val refCount = MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java)

        val queryInterface = stub("onQueryInterface", unknown, FunctionDescriptor.of(I32, ADDR, ADDR, ADDR))
        val addRef = stub("onAddRef", refCount, FunctionDescriptor.of(I32, ADDR))
        val release = stub("onRelease", refCount, FunctionDescriptor.of(I32, ADDR))

        // IAudioSessionEvents: one vtable, shared by every watched session. The
        // instance struct carries a token after its vtable pointer, which is how
        // a callback knows which session it is about without one stub set each.
        val events = arrayOfNulls<MemorySegment>(WasapiAbi.SESSION_EVENTS_VTABLE_SLOTS)
        events[WasapiAbi.QUERY_INTERFACE] = queryInterface
        events[WasapiAbi.ADD_REF] = addRef
        events[WasapiAbi.RELEASE] = release
        events[WasapiAbi.SESSION_EVENTS_ON_DISPLAY_NAME_CHANGED] =
            stub("onDisplayNameChanged", unknown, FunctionDescriptor.of(I32, ADDR, ADDR, ADDR))
        events[WasapiAbi.SESSION_EVENTS_ON_ICON_PATH_CHANGED] =
            stub("onIconPathChanged", unknown, FunctionDescriptor.of(I32, ADDR, ADDR, ADDR))
        events[WasapiAbi.SESSION_EVENTS_ON_SIMPLE_VOLUME_CHANGED] = stub(
            "onSimpleVolumeChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(I32, ADDR, ValueLayout.JAVA_FLOAT, I32, ADDR),
        )
        events[WasapiAbi.SESSION_EVENTS_ON_CHANNEL_VOLUME_CHANGED] = stub(
            "onChannelVolumeChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType,
                MemorySegment::class.java, Int::class.javaPrimitiveType, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR),
        )
        events[WasapiAbi.SESSION_EVENTS_ON_GROUPING_PARAM_CHANGED] =
            stub("onGroupingParamChanged", unknown, FunctionDescriptor.of(I32, ADDR, ADDR, ADDR))
        events[WasapiAbi.SESSION_EVENTS_ON_STATE_CHANGED] = stub(
            "onStateChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType,
            ),
            FunctionDescriptor.of(I32, ADDR, I32),
        )
        events[WasapiAbi.SESSION_EVENTS_ON_SESSION_DISCONNECTED] = stub(
            "onSessionDisconnected",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType,
            ),
            FunctionDescriptor.of(I32, ADDR, I32),
        )
        sessionEventsVtable = vtableOf(arena, events)

        // IAudioSessionNotification: one object, registered on one manager.
        val notify = arrayOfNulls<MemorySegment>(WasapiAbi.SESSION_NOTIFY_VTABLE_SLOTS)
        notify[WasapiAbi.QUERY_INTERFACE] = queryInterface
        notify[WasapiAbi.ADD_REF] = addRef
        notify[WasapiAbi.RELEASE] = release
        notify[WasapiAbi.SESSION_NOTIFY_ON_SESSION_CREATED] = stub(
            "onSessionCreated",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(I32, ADDR, ADDR),
        )
        val notifyInstance = arena.allocate(ADDR, 1)
        notifyInstance.set(ADDR, 0, vtableOf(arena, notify))
        sessionNotification = notifyInstance

        // The manager stays referenced for as long as the registration does:
        // unregistering needs the same object it was registered on.
        val device = defaultRenderDevice() ?: return
        try {
            val manager = activateSessionManager(device) ?: return
            val hr = com.method(
                manager, WasapiAbi.SESSION_MANAGER_REGISTER_NOTIFICATION,
                FunctionDescriptor.of(I32, ADDR, ADDR),
            ).invokeExact(manager, sessionNotification) as Int
            if (hr == WasapiAbi.S_OK) {
                registeredManager = manager
            } else {
                log.info("session notifications unavailable: 0x{}", Integer.toHexString(hr))
                com.release(manager)
            }
        } finally {
            com.release(device)
        }
    }

    private fun vtableOf(arena: Arena, slots: Array<MemorySegment?>): MemorySegment {
        // Every slot filled: a short vtable means the service calls through
        // whatever memory happens to follow it.
        val vtable = arena.allocate(ADDR, slots.size.toLong())
        slots.forEachIndexed { index, stub ->
            vtable.setAtIndex(ADDR, index.toLong(), checkNotNull(stub) { "vtable slot $index unfilled" })
        }
        return vtable
    }

    /**
     * The image name of the process behind a session, which is what the system
     * mixer falls back to and what the libpulse mixer reports as
     * `application.process.binary`. Most applications set no display name at
     * all, so without this every row would be blank.
     */
    private fun processName(processId: Int): String? {
        if (processId == 0) return null
        return runCatching {
            Arena.ofConfined().use { call ->
                val handle = kernel32("OpenProcess", FunctionDescriptor.of(ADDR, I32, I32, I32))
                    .invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, processId) as MemorySegment
                if (handle.address() == 0L) return null
                try {
                    val size = call.allocate(I32)
                    size.set(I32, 0, MAX_PATH_CHARS)
                    val buffer = call.allocate(MAX_PATH_CHARS * 2L, 2)
                    val ok = kernel32(
                        "QueryFullProcessImageNameW",
                        FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR),
                    ).invokeExact(handle, 0, buffer, size) as Int
                    if (ok == 0) return null
                    WasapiCom.readWide(buffer)?.substringAfterLast('\\')?.removeSuffix(".exe")?.ifBlank { null }
                } finally {
                    runCatching { kernel32("CloseHandle", FunctionDescriptor.of(I32, ADDR)).invokeExact(handle) as Int }
                }
            }
        }.getOrNull()
    }

    private fun kernel32(name: String, descriptor: FunctionDescriptor) =
        Linker.nativeLinker().downcallHandle(
            kernel32Lookup.find(name).orElseThrow { IllegalStateException("kernel32 has no $name") },
            descriptor,
        )

    private val kernel32Lookup: SymbolLookup by lazy {
        SymbolLookup.libraryLookup("kernel32.dll", com.arena)
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mixer")

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT

        private val OUR_PID = ProcessHandle.current().pid()

        /** `{ vtable*, token }` -- the token is how one vtable serves many sessions. */
        private const val INSTANCE_SIZE = 16L

        private const val E_FAIL = 0x8000_4005.toInt()

        /** What a COM object answers for an interface it is not. */
        private const val E_NOINTERFACE = 0x8000_4002.toInt()

        private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

        /** Matched to [WasapiCom.MAX_WIDE_BYTES]; a longer buffer could not be read anyway. */
        private const val MAX_PATH_CHARS = 2_048

        private const val SYSTEM_SOUNDS_NAME = "System Sounds"

        /**
         * Ours to choose rather than a system value: an event context is any
         * guid the caller invents. Stamped on every change we make so that our
         * own change arrives back through OnSimpleVolumeChanged identifiable as
         * ours; a null context would make every change look like somebody
         * else's.
         */
        private const val EVENT_CONTEXT = "6F3A1B4C-2E5D-4A87-9C10-1B7E9D4A5C22"

        /** Open a mixer, or null anywhere that is not a Windows with an audio service. */
        fun openOrNull(): AudioMixer? {
            val com = WasapiCom.loadOrNull() ?: return null
            return runCatching {
                com.ensureComOnThisThread()
                val enumerator = Arena.ofConfined().use { call ->
                    val out = call.allocate(ADDR)
                    val hr = com.handle("CoCreateInstance").invokeExact(
                        WasapiCom.guid(call, WasapiAbi.CLSID_MM_DEVICE_ENUMERATOR),
                        MemorySegment.NULL, WasapiAbi.CLSCTX_ALL,
                        WasapiCom.guid(call, WasapiAbi.IID_MM_DEVICE_ENUMERATOR), out,
                    ) as Int
                    if (hr != WasapiAbi.S_OK) throw IllegalStateException("CoCreateInstance: 0x${Integer.toHexString(hr)}")
                    out.get(ADDR, 0)
                }
                if (enumerator.address() == 0L) throw IllegalStateException("no device enumerator")
                runCatching { WasapiMixer(com, enumerator).apply { installNotifications() } }
                    .getOrElse { failure ->
                        // The enumerator belongs to nobody once construction
                        // fails, and closing the arena frees our own memory
                        // rather than the shell's reference to its object.
                        com.release(enumerator)
                        throw failure
                    }
            }.getOrElse {
                log.debug("Windows mixer unavailable: {}", it.message)
                com.close()
                null
            }
        }
    }
}
