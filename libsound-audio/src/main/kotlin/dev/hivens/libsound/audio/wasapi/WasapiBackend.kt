package dev.hivens.libsound.audio.wasapi

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.SinkConfig
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Windows backend: `IMMDeviceEnumerator` for the device list, and a
 * synthesised `IMMNotificationClient` so the shell can tell us when that list
 * changes.
 *
 * This is the one interface the library implements rather than calls, and the
 * machinery for it is libnotify's: a fabricated vtable of upcall stubs behind a
 * `{ vtable* }` object, with a lenient `QueryInterface` that hands the same
 * pointer back for whatever is asked. The shell only ever needs the five
 * notification slots and IUnknown's three, and pretending to be more than that
 * costs nothing it can detect.
 */
internal class WasapiBackend private constructor(
    private val com: WasapiCom,
    private val enumerator: MemorySegment,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.Wasapi")

    override val name: String = "wasapi"

    /**
     * Everything, and honestly. WASAPI is the one backend in this library with
     * no gaps: a sample-accurate playhead, per-application volume the system
     * mixer follows, a named session, a device list worth offering, and events
     * when it changes.
     */
    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_VOLUME,
        Capability.STREAM_IDENTITY,
        Capability.DEVICE_ENUMERATION,
        Capability.DEVICE_SELECTION,
        Capability.DEVICE_EVENTS,
        Capability.DEVICE_POSITION,
    )

    private val closed = AtomicBoolean(false)
    private val sinks = CopyOnWriteArrayList<WasapiSink>()
    private val deviceListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Handlers run here, never on the thread the shell calls us on.
     *
     * The same rule the PulseAudio backend needed, for the same reason: the
     * natural response to a device event is to re-read the device list, and
     * doing that inside the callback re-enters COM on a thread the shell owns
     * while it waits for us to return.
     */
    private val eventDispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-wasapi-events").apply { isDaemon = true }
    }

    /** The synthesised notification object, alive as long as it is registered. */
    private var notificationClient = MemorySegment.NULL

    override fun createSink(config: SinkConfig): AudioSink {
        check(!closed.get()) { "backend is closed" }
        val sink = WasapiSink(com, enumerator, config, SINK_CAPABILITIES)
        sinks.add(sink)
        return sink
    }

    override fun devices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        com.ensureComOnThisThread()
        val defaultId = defaultDeviceId()
        return Arena.ofConfined().use { call ->
            val collection = enumerateEndpoints(call) ?: return@use emptyList()
            try {
                val count = collectionCount(call, collection)
                (0 until count).mapNotNull { index ->
                    val device = collectionItem(call, collection, index) ?: return@mapNotNull null
                    try {
                        val id = deviceId(device) ?: return@mapNotNull null
                        AudioDevice(
                            id = DeviceId(id),
                            // Falls back to the id, which is ugly but unique;
                            // an empty label in a device selector is worse.
                            name = friendlyName(call, device) ?: id,
                            isDefault = id == defaultId,
                        )
                    } finally {
                        com.release(device)
                    }
                }
            } finally {
                com.release(collection)
            }
        }
    }

    override fun defaultDevice(): AudioDevice? {
        val id = defaultDeviceId() ?: return null
        return devices().firstOrNull { it.id.value == id }
    }

    override fun onDevicesChanged(handler: () -> Unit): () -> Unit {
        deviceListeners.add(handler)
        return { deviceListeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        deviceListeners.clear()
        eventDispatch.shutdownNow()
        runCatching { unregisterNotifications() }
        com.release(enumerator)
        com.close()
    }

    // -- the interface we implement ------------------------------------------
    //
    // Public rather than internal for the reason the PulseAudio callbacks are:
    // Kotlin mangles an internal name and findVirtual looks up what is written.

    /** Lenient by design -- the shell only calls the notification slots. */
    fun onQueryInterface(self: MemorySegment, unusedIid: MemorySegment, out: MemorySegment): Int {
        return runCatching {
            out.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, self)
            WasapiAbi.S_OK
        }.getOrDefault(E_FAIL)
    }

    /**
     * The object outlives any refcount the shell keeps: it lives in an arena we
     * close on teardown, after unregistering. Returning a constant is what
     * libnotify's synthesised handlers do, for the same reason.
     */
    fun onAddRef(unusedSelf: MemorySegment): Int = 1

    fun onRelease(unusedSelf: MemorySegment): Int = 1

    fun onDeviceStateChanged(unusedSelf: MemorySegment, unusedId: MemorySegment, unusedState: Int): Int =
        fireDeviceChange()

    fun onDeviceAdded(unusedSelf: MemorySegment, unusedId: MemorySegment): Int = fireDeviceChange()

    fun onDeviceRemoved(unusedSelf: MemorySegment, unusedId: MemorySegment): Int = fireDeviceChange()

    fun onDefaultDeviceChanged(
        unusedSelf: MemorySegment,
        unusedFlow: Int,
        unusedRole: Int,
        unusedId: MemorySegment,
    ): Int = fireDeviceChange()

    /**
     * The last parameter is a `PROPERTYKEY` passed by value -- twenty bytes, so
     * the x64 convention hands it over as a hidden pointer, which is why it is
     * declared as an address. Nothing here reads it: a property change on a
     * device is a reason to re-read the list, not to inspect the field.
     */
    fun onPropertyValueChanged(
        unusedSelf: MemorySegment,
        unusedId: MemorySegment,
        unusedKey: MemorySegment,
    ): Int = WasapiAbi.S_OK

    private fun fireDeviceChange(): Int {
        val handlers = deviceListeners.toList()
        if (handlers.isEmpty()) return WasapiAbi.S_OK
        runCatching {
            eventDispatch.execute {
                handlers.forEach { handler ->
                    runCatching { handler() }.onFailure { log.warn("device listener threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("device event dropped, dispatcher is shut down") }
        return WasapiAbi.S_OK
    }

    // -- building the synthesised object -------------------------------------

    private fun installNotificationClient() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT
        val arena = com.arena

        fun stub(name: String, type: MethodType, descriptor: FunctionDescriptor): MemorySegment =
            linker.upcallStub(
                lookup.findVirtual(WasapiBackend::class.java, name, type).bindTo(this),
                descriptor,
                arena,
            )

        val slots = arrayOfNulls<MemorySegment>(WasapiAbi.NOTIFY_VTABLE_SLOTS)
        slots[WasapiAbi.QUERY_INTERFACE] = stub(
            "onQueryInterface",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(i32, addr, addr, addr),
        )
        slots[WasapiAbi.ADD_REF] = stub(
            "onAddRef",
            MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.of(i32, addr),
        )
        slots[WasapiAbi.RELEASE] = stub(
            "onRelease",
            MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.of(i32, addr),
        )
        slots[WasapiAbi.NOTIFY_ON_DEVICE_STATE_CHANGED] = stub(
            "onDeviceStateChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                MemorySegment::class.java, Int::class.javaPrimitiveType,
            ),
            FunctionDescriptor.of(i32, addr, addr, i32),
        )
        slots[WasapiAbi.NOTIFY_ON_DEVICE_ADDED] = stub(
            "onDeviceAdded",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(i32, addr, addr),
        )
        slots[WasapiAbi.NOTIFY_ON_DEVICE_REMOVED] = stub(
            "onDeviceRemoved",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(i32, addr, addr),
        )
        slots[WasapiAbi.NOTIFY_ON_DEFAULT_DEVICE_CHANGED] = stub(
            "onDefaultDeviceChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(i32, addr, i32, i32, addr),
        )
        slots[WasapiAbi.NOTIFY_ON_PROPERTY_VALUE_CHANGED] = stub(
            "onPropertyValueChanged",
            MethodType.methodType(
                Int::class.javaPrimitiveType, MemorySegment::class.java,
                MemorySegment::class.java, MemorySegment::class.java,
            ),
            FunctionDescriptor.of(i32, addr, addr, addr),
        )

        // Every slot filled: a short vtable means the shell calls through
        // whatever memory happens to follow it.
        val vtable = arena.allocate(addr, WasapiAbi.NOTIFY_VTABLE_SLOTS.toLong())
        slots.forEachIndexed { index, stubPointer ->
            vtable.setAtIndex(addr, index.toLong(), checkNotNull(stubPointer) { "vtable slot $index unfilled" })
        }
        val instance = arena.allocate(addr, 1)
        instance.set(addr, 0, vtable)
        notificationClient = instance

        val hr = com.method(
            enumerator, WasapiAbi.REGISTER_ENDPOINT_NOTIFICATION,
            FunctionDescriptor.of(i32, addr, addr),
        ).invokeExact(enumerator, instance) as Int
        if (hr < 0) {
            log.info("device notifications unavailable: 0x{}", Integer.toHexString(hr))
            notificationClient = MemorySegment.NULL
        }
    }

    private fun unregisterNotifications() {
        val client = notificationClient
        if (client.address() == 0L) return
        notificationClient = MemorySegment.NULL
        com.method(
            enumerator, WasapiAbi.UNREGISTER_ENDPOINT_NOTIFICATION,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(enumerator, client) as Int
    }

    // -- enumeration ---------------------------------------------------------

    private fun enumerateEndpoints(call: Arena): MemorySegment? {
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = com.method(
            enumerator, WasapiAbi.ENUM_AUDIO_ENDPOINTS,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
        ).invokeExact(enumerator, WasapiAbi.E_RENDER, WasapiAbi.DEVICE_STATE_ACTIVE, out) as Int
        if (hr < 0) {
            log.debug("EnumAudioEndpoints failed: 0x{}", Integer.toHexString(hr))
            return null
        }
        val pointer = out.get(ValueLayout.ADDRESS, 0)
        return if (pointer.address() == 0L) null else pointer
    }

    private fun collectionCount(call: Arena, collection: MemorySegment): Int {
        val out = call.allocate(ValueLayout.JAVA_INT)
        val hr = com.method(
            collection, WasapiAbi.COLLECTION_GET_COUNT,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(collection, out) as Int
        return if (hr < 0) 0 else out.get(ValueLayout.JAVA_INT, 0)
    }

    private fun collectionItem(call: Arena, collection: MemorySegment, index: Int): MemorySegment? {
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = com.method(
            collection, WasapiAbi.COLLECTION_ITEM,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
        ).invokeExact(collection, index, out) as Int
        if (hr < 0) return null
        val pointer = out.get(ValueLayout.ADDRESS, 0)
        return if (pointer.address() == 0L) null else pointer
    }

    private fun deviceId(device: MemorySegment): String? = Arena.ofConfined().use { call ->
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = com.method(
            device, WasapiAbi.DEVICE_GET_ID,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(device, out) as Int
        if (hr < 0) return@use null
        val pointer = out.get(ValueLayout.ADDRESS, 0)
        val id = WasapiCom.readWide(pointer)
        // GetId hands over shell-allocated memory; the caller owns it.
        com.coTaskMemFree(pointer)
        id
    }

    private fun defaultDeviceId(): String? {
        com.ensureComOnThisThread()
        return Arena.ofConfined().use { call ->
            val out = call.allocate(ValueLayout.ADDRESS)
            val hr = com.method(
                enumerator, WasapiAbi.GET_DEFAULT_AUDIO_ENDPOINT,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ),
            ).invokeExact(enumerator, WasapiAbi.E_RENDER, WasapiAbi.E_CONSOLE, out) as Int
            if (hr < 0) return@use null
            val device = out.get(ValueLayout.ADDRESS, 0)
            if (device.address() == 0L) return@use null
            try {
                deviceId(device)
            } finally {
                com.release(device)
            }
        }
    }

    /** `PKEY_Device_FriendlyName` out of the device's property store. */
    private fun friendlyName(call: Arena, device: MemorySegment): String? {
        val storeOut = call.allocate(ValueLayout.ADDRESS)
        val openHr = com.method(
            device, WasapiAbi.DEVICE_OPEN_PROPERTY_STORE,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
        ).invokeExact(device, STGM_READ, storeOut) as Int
        if (openHr < 0) return null
        val store = storeOut.get(ValueLayout.ADDRESS, 0)
        if (store.address() == 0L) return null
        try {
            // PROPERTYKEY is { GUID fmtid; DWORD pid } -- 20 bytes, and passed
            // by value, which on x64 means by hidden pointer.
            val key = call.allocate(WasapiAbi.PROPERTYKEY_SIZE, 4)
            val fmtid = WasapiCom.guid(call, WasapiAbi.PKEY_DEVICE_FRIENDLY_NAME_FMTID)
            MemorySegment.copy(fmtid, 0L, key, 0L, 16L)
            key.set(ValueLayout.JAVA_INT, 16L, WasapiAbi.PKEY_DEVICE_FRIENDLY_NAME_PID)

            val variant = call.allocate(WasapiAbi.PROPVARIANT_SIZE, 8)
            variant.fill(0)
            val hr = com.method(
                store, WasapiAbi.PROPERTY_STORE_GET_VALUE,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
            ).invokeExact(store, key, variant) as Int
            if (hr < 0) return null
            if (variant.get(ValueLayout.JAVA_SHORT, WasapiAbi.PROPVARIANT_VT).toInt() != WasapiAbi.VT_LPWSTR) {
                return null
            }
            val pointer = variant.get(ValueLayout.ADDRESS, WasapiAbi.PROPVARIANT_VALUE)
            val name = WasapiCom.readWide(pointer)
            // For VT_LPWSTR this is what PropVariantClear would do, without
            // binding a symbol that lives in a different DLL per Windows
            // version.
            com.coTaskMemFree(pointer)
            return name
        } finally {
            com.release(store)
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Wasapi")

        private const val STGM_READ = 0
        // Narrowed from the hex, never negated by hand -- see WasapiAbi.
        private val E_FAIL: Int = 0x80004005u.toInt()

        /** A sink cannot enumerate, select or subscribe; only the backend can. */
        private val SINK_CAPABILITIES = Capabilities.of(
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
            Capability.DEVICE_POSITION,
        )

        /**
         * Connect to the audio endpoints, or return null off Windows and
         * wherever the service is not running -- both of which are ordinary
         * answers the selection falls back on.
         */
        fun connectOrNull(): AudioBackend? {
            val com = WasapiCom.loadOrNull() ?: return null
            return runCatching {
                com.ensureComOnThisThread()
                val enumerator = Arena.ofConfined().use { call ->
                    val out = call.allocate(ValueLayout.ADDRESS)
                    val hr = com.handle("CoCreateInstance").invokeExact(
                        WasapiCom.guid(call, WasapiAbi.CLSID_MM_DEVICE_ENUMERATOR),
                        MemorySegment.NULL,
                        WasapiAbi.CLSCTX_ALL,
                        WasapiCom.guid(call, WasapiAbi.IID_MM_DEVICE_ENUMERATOR),
                        out,
                    ) as Int
                    if (hr < 0) error("CoCreateInstance(MMDeviceEnumerator): 0x${Integer.toHexString(hr)}")
                    out.get(ValueLayout.ADDRESS, 0)
                }
                check(enumerator.address() != 0L) { "MMDeviceEnumerator came back null" }
                WasapiBackend(com, enumerator).apply { installNotificationClient() }
            }.getOrElse {
                log.debug("WASAPI unavailable: {}", it.message)
                com.close()
                null
            }
        }
    }
}
