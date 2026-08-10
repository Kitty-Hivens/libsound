package dev.hivens.libsound.audio.coreaudio

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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The CoreAudio backend: what macOS can do, and openly not what it cannot.
 *
 * Devices, a default that can be followed or overridden, events when either
 * changes, and an honest playhead. No per-application volume and no stream
 * identity, because the platform has neither in any public API -- so neither is
 * claimed, and a settings screen that asks first will not draw controls that
 * could not work.
 *
 * ## Why a device is identified by its uid
 *
 * The obvious identity is the `AudioObjectID`, and it is wrong: it is a
 * per-boot handle. The next obvious one is the name, and on the machine this was
 * first measured against two devices shared the name "Apple Virtual Sound
 * Device" -- so a stored name selects the wrong device, or an input one. The uid
 * is the only identity that survives a reboot and distinguishes two devices that
 * a person cannot tell apart either.
 */
internal class CoreAudioBackend private constructor(
    private val lib: CoreAudioLibrary,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.CoreAudio")

    override val name: String = "coreaudio"

    override val capabilities: Capabilities = Capabilities.of(
        Capability.DEVICE_ENUMERATION,
        Capability.DEVICE_SELECTION,
        Capability.DEVICE_EVENTS,
        Capability.DEVICE_POSITION,
    )

    private val closed = AtomicBoolean(false)

    private val sinks = CopyOnWriteArrayList<CoreAudioSink>()
    private val deviceListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Handlers never run on the thread CoreAudio delivers the notification on.
     * That thread belongs to the HAL, and a handler whose natural first move is
     * to re-read the device list would be calling back into the HAL from inside
     * its own callback.
     */
    private val eventDispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-coreaudio-events").apply { isDaemon = true }
    }

    private lateinit var listenerStub: MemorySegment

    override fun createSink(config: SinkConfig): AudioSink {
        val sink = CoreAudioSink(lib, config, SINK_CAPABILITIES, ::objectIdForUid)
        sinks.add(sink)
        return sink
    }

    override fun devices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        val default = defaultOutputId()
        return deviceIds().mapNotNull { id ->
            // An input-only device is not an output device. Both sides of a
            // duplex interface appear in the same list, and the machine this was
            // measured on has one of each under the same name.
            if (outputChannels(id) <= 0) return@mapNotNull null
            val uid = stringProperty(id, CoreAudioAbi.PROPERTY_DEVICE_UID) ?: return@mapNotNull null
            AudioDevice(
                id = DeviceId(uid),
                name = stringProperty(id, CoreAudioAbi.PROPERTY_NAME) ?: uid,
                isDefault = id == default,
            )
        }
    }

    override fun defaultDevice(): AudioDevice? = devices().firstOrNull { it.isDefault }

    override fun onDevicesChanged(handler: () -> Unit): () -> Unit {
        deviceListeners.add(handler)
        return { deviceListeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        deviceListeners.clear()
        runCatching { removeListeners() }
        // Drained, not killed: a handler re-reading the device list is calling
        // into the HAL, and shutdownNow cannot interrupt a native call anyway.
        eventDispatch.shutdown()
        runCatching { eventDispatch.awaitTermination(2, TimeUnit.SECONDS) }
        lib.close()
    }

    // -- the property listener, on a thread belonging to the HAL --------------

    // Public rather than internal, for the same reason as the render callback:
    // Kotlin mangles an internal name and findVirtual looks up what is written.

    fun onPropertyChanged(
        unusedObjectId: Int,
        unusedCount: Int,
        unusedAddresses: MemorySegment,
        unusedClientData: MemorySegment,
    ): Int {
        // Deliberately coarse. The addresses say which property moved, but every
        // consumer of this signal re-reads the whole list anyway, and a diff
        // would be state to keep correct for no gain.
        val handlers = deviceListeners.toList()
        if (handlers.isNotEmpty()) {
            runCatching {
                eventDispatch.execute {
                    handlers.forEach { handler ->
                        runCatching { handler() }.onFailure { log.warn("device listener threw: {}", it.message) }
                    }
                }
            }.onFailure { log.debug("device event dropped, dispatcher is shut down") }
        }
        return CoreAudioAbi.NO_ERROR
    }

    // -- internals ------------------------------------------------------------

    private fun deviceIds(): List<Int> = Arena.ofConfined().use { call ->
        val address = call.allocate(CoreAudioAbi.ADDRESS_SIZE, 4)
        lib.address(address, CoreAudioAbi.PROPERTY_DEVICES, CoreAudioAbi.SCOPE_GLOBAL_SELECTOR)
        val size = call.allocate(ValueLayout.JAVA_INT)
        val sized = lib.handle("AudioObjectGetPropertyDataSize").invokeExact(
            CoreAudioAbi.SYSTEM_OBJECT, address, 0, MemorySegment.NULL, size,
        ) as Int
        val bytes = size.get(ValueLayout.JAVA_INT, 0)
        if (sized != CoreAudioAbi.NO_ERROR || bytes <= 0) return emptyList()

        val out = call.allocate(bytes.toLong(), 4)
        val read = lib.handle("AudioObjectGetPropertyData").invokeExact(
            CoreAudioAbi.SYSTEM_OBJECT, address, 0, MemorySegment.NULL, size, out,
        ) as Int
        if (read != CoreAudioAbi.NO_ERROR) return emptyList()
        (0 until bytes / 4).map { out.get(ValueLayout.JAVA_INT, it * 4L) }
    }

    private fun defaultOutputId(): Int = Arena.ofConfined().use { call ->
        val address = call.allocate(CoreAudioAbi.ADDRESS_SIZE, 4)
        lib.address(address, CoreAudioAbi.PROPERTY_DEFAULT_OUTPUT_DEVICE, CoreAudioAbi.SCOPE_GLOBAL_SELECTOR)
        val out = call.allocate(ValueLayout.JAVA_INT)
        val size = call.allocate(ValueLayout.JAVA_INT)
        size.set(ValueLayout.JAVA_INT, 0, 4)
        val rc = lib.handle("AudioObjectGetPropertyData").invokeExact(
            CoreAudioAbi.SYSTEM_OBJECT, address, 0, MemorySegment.NULL, size, out,
        ) as Int
        if (rc != CoreAudioAbi.NO_ERROR) 0 else out.get(ValueLayout.JAVA_INT, 0)
    }

    /** Sum of the output channels across the device's streams; zero means an input. */
    private fun outputChannels(deviceId: Int): Int = Arena.ofConfined().use { call ->
        val address = call.allocate(CoreAudioAbi.ADDRESS_SIZE, 4)
        lib.address(address, CoreAudioAbi.PROPERTY_STREAM_CONFIGURATION, CoreAudioAbi.SCOPE_OUTPUT_SELECTOR)
        val size = call.allocate(ValueLayout.JAVA_INT)
        val sized = lib.handle("AudioObjectGetPropertyDataSize").invokeExact(
            deviceId, address, 0, MemorySegment.NULL, size,
        ) as Int
        val bytes = size.get(ValueLayout.JAVA_INT, 0)
        if (sized != CoreAudioAbi.NO_ERROR || bytes <= 0) return 0

        val list = call.allocate(bytes.toLong(), 8)
        val read = lib.handle("AudioObjectGetPropertyData").invokeExact(
            deviceId, address, 0, MemorySegment.NULL, size, list,
        ) as Int
        if (read != CoreAudioAbi.NO_ERROR) return 0
        val buffers = list.get(ValueLayout.JAVA_INT, CoreAudioAbi.BUFFER_LIST_NUMBER_BUFFERS)
        var channels = 0
        for (index in 0 until buffers) {
            val offset = CoreAudioAbi.BUFFER_LIST_BUFFERS + CoreAudioAbi.BUFFER_SIZE * index
            if (offset + CoreAudioAbi.BUFFER_SIZE > bytes) break
            channels += list.get(ValueLayout.JAVA_INT, offset + CoreAudioAbi.BUFFER_NUMBER_CHANNELS)
        }
        channels
    }

    private fun stringProperty(deviceId: Int, selector: Int): String? = Arena.ofConfined().use { call ->
        val address = call.allocate(CoreAudioAbi.ADDRESS_SIZE, 4)
        lib.address(address, selector, CoreAudioAbi.SCOPE_GLOBAL_SELECTOR)
        val out = call.allocate(ValueLayout.ADDRESS)
        val size = call.allocate(ValueLayout.JAVA_INT)
        size.set(ValueLayout.JAVA_INT, 0, 8)
        val rc = lib.handle("AudioObjectGetPropertyData").invokeExact(
            deviceId, address, 0, MemorySegment.NULL, size, out,
        ) as Int
        if (rc != CoreAudioAbi.NO_ERROR) return null
        lib.cfStringAndRelease(out.get(ValueLayout.ADDRESS, 0))
    }

    /**
     * The per-boot handle for a stored uid, which is what pinning a device on an
     * output unit needs. Matched by walking the list rather than through
     * `kAudioHardwarePropertyTranslateUIDToDevice`: one fewer selector to be
     * wrong about, on a path taken once per open.
     */
    private fun objectIdForUid(uid: String): Int? =
        deviceIds().firstOrNull { stringProperty(it, CoreAudioAbi.PROPERTY_DEVICE_UID) == uid }

    private fun installListeners() {
        listenerStub = Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findVirtual(
                CoreAudioBackend::class.java, "onPropertyChanged",
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    MemorySegment::class.java, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
            lib.arena,
        )
        forEachWatchedProperty { address ->
            lib.handle("AudioObjectAddPropertyListener").invokeExact(
                CoreAudioAbi.SYSTEM_OBJECT, address, listenerStub, MemorySegment.NULL,
            ) as Int
        }
    }

    private fun removeListeners() {
        if (!::listenerStub.isInitialized) return
        forEachWatchedProperty { address ->
            lib.handle("AudioObjectRemovePropertyListener").invokeExact(
                CoreAudioAbi.SYSTEM_OBJECT, address, listenerStub, MemorySegment.NULL,
            ) as Int
        }
    }

    /** Devices appearing or leaving, and the default moving between them. */
    private inline fun forEachWatchedProperty(body: (MemorySegment) -> Int) {
        Arena.ofConfined().use { call ->
            val address = call.allocate(CoreAudioAbi.ADDRESS_SIZE, 4)
            for (selector in intArrayOf(
                CoreAudioAbi.PROPERTY_DEVICES,
                CoreAudioAbi.PROPERTY_DEFAULT_OUTPUT_DEVICE,
            )) {
                lib.address(address, selector, CoreAudioAbi.SCOPE_GLOBAL_SELECTOR)
                body(address)
            }
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.CoreAudio")

        /**
         * What a sink can do, which is not what the backend can do. A sink
         * cannot enumerate devices or subscribe to their events, and handing it
         * the backend's set would claim both.
         */
        private val SINK_CAPABILITIES = Capabilities.of(Capability.DEVICE_POSITION)

        /** Open the backend, or null anywhere that is not a macOS with CoreAudio. */
        fun connectOrNull(): AudioBackend? {
            val lib = CoreAudioLibrary.loadOrNull() ?: run {
                log.debug("CoreAudio not loadable")
                return null
            }
            return runCatching {
                CoreAudioBackend(lib).apply { installListeners() }
            }.getOrElse {
                log.debug("CoreAudio backend setup failed: {}", it.message)
                lib.close()
                null
            }
        }
    }
}
