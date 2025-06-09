package com.yinnho.upnpcast.internal.core

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.DLNACast.Device

import com.yinnho.upnpcast.DLNACast.PlaybackState
import com.yinnho.upnpcast.DLNACast.State


import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.SsdpDeviceDiscovery

import com.yinnho.upnpcast.internal.media.DlnaMediaController
import com.yinnho.upnpcast.internal.localcast.LocalCastManager
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlinx.coroutines.*

/**
 * Core manager for DLNA casting functionality
 * Responsible for unified management of device discovery, media control, and casting operations
 * 
 * Core management logic extracted from DLNACastImpl
 */
internal class CoreManager {
    
    companion object {
        private const val TAG = "CoreManager"
        
        private val cacheManager = CacheManager(ScopeManager.appScope)
        
        private val devices = ConcurrentHashMap<String, RemoteDevice>()
        private var ssdpDiscovery: SsdpDeviceDiscovery? = null
        @Volatile
        private var currentDevice: RemoteDevice? = null
        private var contextRef: WeakReference<Context>? = null
        
        @Volatile
        private var currentDeviceListCallback: ((devices: List<Device>) -> Unit)? = null
        
        @Volatile
        private var callbackTimeoutJob: Job? = null
        
        @Volatile
        private var searchCompleted = false
        
        private val notifiedDeviceIds = ConcurrentSkipListSet<String>()
        
        /**
         * Initialize core manager with application context
         */
        fun init(context: Context) {
            contextRef = WeakReference(context.applicationContext)
            ssdpDiscovery = SsdpDeviceDiscovery(
                onDeviceFound = { device ->
                    addDevice(device)
                }
            )
            Log.i(TAG, "CoreManager initialized")
        }
        
        /**
         * Search for available DLNA devices
         */
        fun search(timeout: Long, callback: (devices: List<Device>) -> Unit) {
            ensureInitialized {
                cleanupSearchCallback()
                
                val foundDevices = mutableListOf<Device>()
                
                currentDeviceListCallback = { devices ->
                    foundDevices.clear()
                    foundDevices.addAll(devices)
                    callback(devices)
                }
                
                searchCompleted = false
                notifiedDeviceIds.clear()
                ssdpDiscovery?.startSearch()
                
                callbackTimeoutJob = ScopeManager.uiScope.launch {
                    delay(timeout)
                    searchCompleted = true
                    callback(foundDevices)
                    cleanupSearchCallback()
                }
            }
        }
        
        /**
         * Cast media to available device (auto-select best device)
         */
        fun cast(url: String, title: String?, callback: (Boolean) -> Unit) {
            ensureInitialized {
                val availableDevices = getAllDevices()
                if (availableDevices.isNotEmpty()) {
                    val bestDevice = selectBestDevice(availableDevices)
                    connectAndPlay(bestDevice, url, title ?: "Media", callback)
                } else {
                    Log.i(TAG, "No devices available, searching first...")
                    search(3000) { devices ->
                        if (devices.isNotEmpty()) {
                            val bestDevice = selectBestDevice(devices)
                            connectAndPlay(bestDevice, url, title ?: "Media", callback)
                        } else {
                            Log.w(TAG, "No devices found after search")
                            callback(false)
                        }
                    }
                }
            }
        }
        
        /**
         * Cast media to specific device
         */
        fun castToDevice(device: Device, url: String, title: String?, callback: (Boolean) -> Unit) {
            ensureInitialized {
                connectAndPlay(device, url, title ?: "Media", callback)
            }
        }
        
        /**
         * Connect to device and start media playback
         */
        fun connectAndPlay(device: Device, url: String, title: String, callback: (success: Boolean) -> Unit) {
            try {
                val remoteDevice = devices[device.id] ?: throw IllegalArgumentException("Device not found: ${device.id}")
                val services = remoteDevice.details["services"] as? List<*>
                if (!services.isNullOrEmpty()) {
                    currentDevice = remoteDevice
                    ScopeManager.appScope.launch {
                        try {
                            val controller = DlnaMediaController.getController(remoteDevice)
                            val success = controller.playMediaDirect(url, title)
                            withContext(Dispatchers.Main) {
                                callback(success)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to play media: ${e.message}")
                            withContext(Dispatchers.Main) {
                                callback(false)
                            }
                        }
                    }
                } else {
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect and play: ${e.message}")
                callback(false)
            }
        }
        

        
        /**
         * Coroutine version of media control operations
         */
        suspend fun controlMediaSuspend(action: String, value: Any? = null): Boolean {
            return executeWithDevice { device ->
                withContext(Dispatchers.IO) {
                    try {
                        val controller = DlnaMediaController.getController(device)
                        controller.control(action, value)
                    } catch (e: Exception) {
                        Log.e(TAG, "Control failed: $action - ${e.message}")
                        false
                    }
                }
            } ?: false
        }


        
        /**
         * Get current playback progress
         */
        fun getProgress(callback: (currentMs: Long, totalMs: Long, success: Boolean) -> Unit) {
            cacheManager.getProgress(currentDevice, callback)
        }
        
        /**
         * Get current volume level and mute state
         */
        fun getVolume(callback: (volume: Int?, isMuted: Boolean?, success: Boolean) -> Unit) {
            cacheManager.getVolume(currentDevice, callback)
        }
        
        /**
         * Get current DLNA casting state
         */
        fun getCurrentState(): State {
            val device = currentDevice?.let { convertToDevice(it) }
            val playbackState = if (device != null) PlaybackState.PLAYING else PlaybackState.IDLE
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
         * Get real-time progress without cache
         */
        fun getProgressRealtime(callback: (currentMs: Long, totalMs: Long, success: Boolean) -> Unit) {
            currentDevice?.let { device ->
                cacheManager.refreshProgressCache(device) { success ->
                    if (success) {
                        cacheManager.getProgress(device, callback)
                    } else {
                        callback(0L, 0L, false)
                    }
                }
            } ?: callback(0L, 0L, false)
        }
        
        /**
         * Manually refresh volume cache from device
         */
        fun refreshVolumeCache(callback: (success: Boolean) -> Unit = {}) {
            currentDevice?.let { device ->
                cacheManager.refreshVolumeCache(device, callback)
            } ?: callback(false)
        }
        
        /**
         * Manually refresh progress cache from device
         */
        fun refreshProgressCache(callback: (success: Boolean) -> Unit = {}) {
            currentDevice?.let { device ->
                cacheManager.refreshProgressCache(device, callback)
            } ?: callback(false)
        }
        
        /**
         * Clear progress cache (call when switching media)
         */
        fun clearProgressCache() {
            cacheManager.clearAll()
        }
        
        /**
         * Cast local file to specified device
         */
        fun castLocalFileToDevice(filePath: String, device: Device, title: String?, callback: (success: Boolean, message: String) -> Unit) {
            ensureInitialized {
                val context = contextRef?.get()
                if (context == null) {
                    callback(false, "Context has been released, please reinitialize")
                    return@ensureInitialized
                }
                
                val remoteDevice = devices[device.id]
                if (remoteDevice == null) {
                    callback(false, "Device not found: ${device.id}")
                    return@ensureInitialized
                }
                
                currentDevice = remoteDevice
                LocalCastManager.castLocalFile(context, filePath, remoteDevice, title, ScopeManager.appScope, callback)
            }
        }
        
        /**
         * Scan local video files on device
         */
        fun scanLocalVideos(context: Context, callback: (videos: List<com.yinnho.upnpcast.DLNACast.LocalVideo>) -> Unit) {
            LocalCastManager.scanLocalVideos(context, callback)
        }
        
        /**
         * Get URL for local file serving
         */
        fun getLocalFileUrl(filePath: String): String? {
            val context = contextRef?.get() ?: return null
            return LocalCastManager.getLocalFileUrl(context, filePath)
        }
        

        
        /**
         * Get all discovered devices
         */
        fun getAllDevices(): List<Device> {
            return devices.values.map { convertToDevice(it) }
                .sortedWith(compareByDescending<Device> { it.isTV }.thenBy { it.name })
        }
        
        /**
         * Find device by ID
         */
        fun findDevice(deviceId: String): Device? {
            return devices[deviceId]?.let { convertToDevice(it) }
        }
        
        /**
         * Select best device (prioritize TV devices)
         */
        fun selectBestDevice(devices: List<Device>): Device {
            return devices.find { it.isTV } ?: devices.first()
        }
        
        /**
         * Get application context
         */
        fun getContext(): Context? {
            return contextRef?.get()
        }
        
        /**
         * Clean up all resources and stop services
         */
        fun cleanup() {
            cleanupSearchCallback()
            

            
            ssdpDiscovery?.shutdown()
            ssdpDiscovery = null
            devices.clear()
            currentDevice = null
            contextRef = null
            searchCompleted = false
            notifiedDeviceIds.clear()
            
            cacheManager.clearAll()
            
            ScopeManager.cleanup()
            
            DlnaMediaController.clearAllControllers()
            LocalCastManager.cleanup()
            Log.i(TAG, "CoreManager cleaned up")
        }
        
        /**
         * Execute action with current connected device
         */
        private inline fun <T> executeWithDevice(action: (RemoteDevice) -> T): T? {
            return currentDevice?.let(action)
        }
        

        
        private fun ensureInitialized(action: () -> Unit) {
            if (ssdpDiscovery == null) {
                contextRef?.get()?.let { context ->
                    init(context)
                } ?: return
            }
            action()
        }
        
        private fun convertToDevice(remoteDevice: RemoteDevice): Device {
            val manufacturer = remoteDevice.manufacturer.lowercase()
            val model = (remoteDevice.details["modelName"] as? String ?: "").lowercase()
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
        
        private fun addDevice(device: RemoteDevice) {
            devices[device.id] = device
            notifyNewDevicesOnly()
        }
        
        private fun notifyNewDevicesOnly() {
            if (!searchCompleted) {
                currentDeviceListCallback?.let { callback ->
                    val allDevices = getAllDevices()
                    callback(allDevices)
                }
            }
        }
        
        /**
         * Clean up search callback and timeout job to prevent memory leaks
         */
        private fun cleanupSearchCallback() {
            try {
                callbackTimeoutJob?.cancel()
                callbackTimeoutJob = null
                currentDeviceListCallback = null
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up search callback: ${e.message}")
            }
        }

    }
} 