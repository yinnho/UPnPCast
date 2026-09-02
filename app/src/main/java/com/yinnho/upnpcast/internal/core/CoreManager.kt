package com.yinnho.upnpcast.internal.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast.Device
import com.yinnho.upnpcast.DLNACast.PlaybackState
import com.yinnho.upnpcast.DLNACast.State
import com.yinnho.upnpcast.internal.UPnPException
import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.SsdpDeviceDiscovery
import com.yinnho.upnpcast.internal.localcast.LocalCastManager
import com.yinnho.upnpcast.internal.media.DlnaMediaController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Core manager for DLNA casting functionality.
 * One instance owns the whole casting stack (scope, discovery, controllers,
 * caches) so its lifetime is explicit and re-initialization cannot leave
 * stale static state behind.
 */
internal class CoreManager(private val appContext: Context) {

    companion object {
        private const val TAG = "CoreManager"
    }

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("UPnPCast")
    )

    private val cacheManager = CacheManager(scope)
    private val devices = ConcurrentHashMap<String, RemoteDevice>()
    private val controllers = ConcurrentHashMap<String, DlnaMediaController>()

    @Volatile
    private var currentDevice: RemoteDevice? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    private val ssdpDiscovery = SsdpDeviceDiscovery(
        onDeviceFound = { device -> devices[device.id] = device },
        parseScope = scope
    )

    init {
        acquireMulticastLock(appContext)
        Log.i(TAG, "CoreManager initialized")
    }

    /**
     * Hold a multicast lock while active: Android Wi-Fi stacks filter
     * multicast traffic by default, which silently drops SSDP NOTIFY
     * messages and M-SEARCH responses routed as multicast.
     */
    private fun acquireMulticastLock(context: Context) {
        releaseMulticastLock()
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: run {
            Log.w(TAG, "WifiManager unavailable, multicast reception may be filtered")
            return
        }
        multicastLock = wifi.createMulticastLock("UPnPCast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock: ${e.message}")
        }
        multicastLock = null
    }

    private fun getController(device: RemoteDevice): DlnaMediaController =
        controllers.getOrPut(device.id) { DlnaMediaController(device) }

    private fun currentController(): DlnaMediaController? = currentDevice?.let { getController(it) }

    /**
     * Search for available DLNA devices, returning the complete list found
     * within [timeout]
     */
    suspend fun search(timeout: Long): List<Device> {
        ssdpDiscovery.startSearch()
        delay(timeout)
        return getAllDevices()
    }

    /**
     * Cast media to best available device (auto-select best device)
     */
    suspend fun cast(url: String, title: String?, options: CastOptions): Boolean {
        val candidates = getAllDevices().ifEmpty {
            Log.i(TAG, "No devices available, searching first...")
            search(3000)
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "No devices found after search")
            return false
        }
        return connectAndPlay(selectBestDevice(candidates), url, title ?: "Media", options)
    }

    /**
     * Cast media to specific device
     */
    suspend fun castToDevice(device: Device, url: String, title: String?, options: CastOptions): Boolean =
        connectAndPlay(device, url, title ?: "Media", options)

    /**
     * Connect to device and start media playback
     */
    private suspend fun connectAndPlay(
        device: Device,
        url: String,
        title: String,
        options: CastOptions
    ): Boolean {
        val remoteDevice = devices[device.id] ?: run {
            Log.e(TAG, "Device not found: ${device.id}")
            return false
        }
        if (remoteDevice.services.isEmpty()) {
            Log.e(TAG, "Device exposes no services: ${device.id}")
            return false
        }

        setCurrentDevice(remoteDevice)
        return try {
            getController(remoteDevice).playMediaDirect(url, title, options = options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play media: ${e.message}")
            false
        }
    }

    /**
     * Coroutine version of media control operations
     */
    suspend fun controlMedia(action: String, value: Any? = null): Boolean {
        val device = currentDevice ?: return false
        return try {
            getController(device).control(action, value)
        } catch (e: Exception) {
            Log.e(TAG, "Control failed: $action - ${e.message}")
            false
        }
    }

    /**
     * Get current playback progress
     */
    suspend fun getProgress(): Pair<Long, Long>? = cacheManager.getProgress(currentController())

    /**
     * Get current volume level and mute state
     */
    suspend fun getVolume(): Pair<Int?, Boolean?>? = cacheManager.getVolume(currentController())

    /**
     * Get current DLNA casting state
     *
     * The playback state reflects the last transport state observed via
     * GetTransportInfo; call [getPlaybackState] for a live query.
     */
    fun getCurrentState(): State {
        val device = currentDevice?.let { convertToDevice(it) }
        val playbackState = if (device != null) {
            mapTransportState(cacheManager.cachedTransportState)
        } else {
            PlaybackState.IDLE
        }
        val (volume, muted) = cacheManager.getVolumeState()

        return State(
            isConnected = device != null,
            currentDevice = device,
            playbackState = playbackState,
            volume = if (volume >= 0) volume else -1,
            isMuted = muted
        )
    }

    /**
     * Query the live transport state from the connected device
     */
    suspend fun getPlaybackState(): PlaybackState {
        val device = currentDevice ?: return PlaybackState.IDLE
        return try {
            val state = getController(device).getTransportInfo()
            cacheManager.updateTransportState(state)
            mapTransportState(state)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playback state: ${e.message}")
            PlaybackState.IDLE
        }
    }

    private fun mapTransportState(transportState: String?): PlaybackState = when (transportState) {
        "PLAYING" -> PlaybackState.PLAYING
        "PAUSED_PLAYBACK" -> PlaybackState.PAUSED
        "TRANSITIONING" -> PlaybackState.BUFFERING
        "STOPPED" -> PlaybackState.STOPPED
        "NO_MEDIA_PRESENT", null -> PlaybackState.IDLE
        else -> PlaybackState.IDLE
    }

    /**
     * Get real-time progress without cache
     */
    suspend fun getProgressRealtime(): Pair<Long, Long>? {
        val controller = currentController() ?: return null
        return if (cacheManager.refreshProgressCache(controller)) {
            cacheManager.getProgress(controller)
        } else {
            null
        }
    }

    /**
     * Manually refresh volume cache from device
     */
    suspend fun refreshVolumeCache(): Boolean {
        val controller = currentController() ?: return false
        return cacheManager.refreshVolumeCache(controller)
    }

    /**
     * Manually refresh progress cache from device
     */
    suspend fun refreshProgressCache(): Boolean {
        val controller = currentController() ?: return false
        return cacheManager.refreshProgressCache(controller)
    }

    /**
     * Clear cached state (call when switching devices or media)
     */
    fun clearProgressCache() {
        cacheManager.clearAll()
    }

    /**
     * Cast local file to specified device
     *
     * @throws UPnPException on file, server or device failures
     */
    suspend fun castLocalFile(filePath: String, device: Device, title: String?, options: CastOptions) {
        val remoteDevice = devices[device.id]
            ?: throw UPnPException.DeviceError("Device not found: ${device.id}")
        setCurrentDevice(remoteDevice)
        LocalCastManager.castLocalFile(appContext, filePath, getController(remoteDevice), title, options)
    }

    /**
     * Get all discovered devices
     */
    fun getAllDevices(): List<Device> {
        return devices.values.map { convertToDevice(it) }
            .sortedWith(compareByDescending<Device> { it.isTV }.thenBy { it.name })
    }

    /**
     * Select best device (prioritize TV devices)
     */
    fun selectBestDevice(devices: List<Device>): Device {
        return devices.find { it.isTV } ?: devices.first()
    }

    /**
     * Release all resources and stop services
     */
    fun shutdown() {
        ssdpDiscovery.shutdown()
        releaseMulticastLock()
        devices.clear()
        currentDevice = null

        cacheManager.clearAll()

        controllers.values.forEach { it.release() }
        controllers.clear()

        scope.cancel()
        LocalCastManager.cleanup()
        Log.i(TAG, "CoreManager shut down")
    }

    /**
     * Set the active device, isolating caches between devices: switching
     * devices drops all cached state, re-casting on the same device
     * drops only progress (media changed, device volume still valid)
     */
    private fun setCurrentDevice(remoteDevice: RemoteDevice) {
        if (currentDevice?.id != remoteDevice.id) {
            cacheManager.clearAll()
        } else {
            cacheManager.clearProgress()
        }
        currentDevice = remoteDevice
    }

    private fun convertToDevice(remoteDevice: RemoteDevice): Device {
        val manufacturer = remoteDevice.manufacturer.lowercase()
        val model = remoteDevice.model.lowercase()
        val isTV = manufacturer.contains("tv") || model.contains("tv") ||
            manufacturer.contains("samsung") || manufacturer.contains("lg") ||
            manufacturer.contains("sony") || manufacturer.contains("xiaomi")
        return Device(
            id = remoteDevice.id,
            name = remoteDevice.displayName,
            address = remoteDevice.address,
            isTV = isTV
        )
    }
}
