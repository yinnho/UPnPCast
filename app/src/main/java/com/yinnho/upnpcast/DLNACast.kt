package com.yinnho.upnpcast

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.yinnho.upnpcast.internal.core.CoreManager
import com.yinnho.upnpcast.internal.UPnPException

/**
 * Modern UPnP/DLNA casting interface (pure coroutine version)
 * Architecture: DLNACast -> CoreManager -> DlnaMediaController
 */
object DLNACast {
    
    /**
     * Generic coroutine converter for single return value
     */
    private suspend inline fun <T> suspendOnce(
        crossinline block: (callback: (T) -> Unit) -> Unit
    ): T = suspendCancellableCoroutine { cont ->
        var resumed = false
        block { result ->
            if (!resumed) {
                resumed = true
                cont.resume(result)
            }
        }
    }
    
    /**
     * Generic coroutine converter for success/failure pattern
     */
    private suspend inline fun <T> suspendWithSuccess(
        crossinline block: (callback: (T, success: Boolean) -> Unit) -> Unit
    ): T? = suspendCancellableCoroutine { cont ->
        var resumed = false
        block { result, success ->
            if (!resumed) {
                resumed = true
                if (success) cont.resume(result) else cont.resume(null)
            }
        }
    }
    
    /**
     * Generic coroutine converter for triple parameter pattern
     */
    private suspend inline fun <T1, T2> suspendWithTriple(
        crossinline block: (callback: (T1, T2, success: Boolean) -> Unit) -> Unit
    ): Pair<T1, T2>? = suspendCancellableCoroutine { cont ->
        var resumed = false
        block { param1, param2, success ->
            if (!resumed) {
                resumed = true
                if (success) cont.resume(Pair(param1, param2)) else cont.resume(null)
            }
        }
    }
    
    data class Device(
        val id: String,
        val name: String,
        val address: String,
        val isTV: Boolean
    )
    
    enum class PlaybackState {
        IDLE, PLAYING, PAUSED, STOPPED, BUFFERING, ERROR
    }
    
    enum class MediaAction(val value: String) {
        PLAY("play"),
        PAUSE("pause"),
        STOP("stop"),
        VOLUME("volume"),
        MUTE("mute"),
        SEEK("seek")
    }
    
    data class State(
        val isConnected: Boolean,
        val currentDevice: Device?,
        val playbackState: PlaybackState,
        val volume: Int = -1,
        val isMuted: Boolean = false
    ) {
        val isPlaying: Boolean get() = playbackState == PlaybackState.PLAYING
        val isPaused: Boolean get() = playbackState == PlaybackState.PAUSED
        val isIdle: Boolean get() = playbackState == PlaybackState.IDLE
    }
    
    data class LocalVideo(
        val id: String,
        val title: String,
        val path: String,
        val duration: String,
        val size: String,
        val durationMs: Long
    )
    
    /**
     * Generic media control method
     */
    suspend fun control(action: MediaAction, value: Any? = null): Boolean {
        return CoreManager.controlMediaSuspend(action.value, value)
    }
    
    /**
     * Convenient control methods
     */
    suspend fun play(): Boolean = control(MediaAction.PLAY)
    suspend fun pause(): Boolean = control(MediaAction.PAUSE)
    suspend fun stop(): Boolean = control(MediaAction.STOP)
    suspend fun setVolume(volume: Int): Boolean = control(MediaAction.VOLUME, volume)
    suspend fun setMute(mute: Boolean): Boolean = control(MediaAction.MUTE, mute)
    suspend fun seek(positionMs: Long): Boolean = control(MediaAction.SEEK, positionMs)
    
    /**
     * Search for DLNA devices
     */
    suspend fun search(timeout: Long = 5000): List<Device> = 
        suspendOnce { callback -> CoreManager.search(timeout, callback) }
    
    /**
     * Cast media to best available device
     */
    suspend fun cast(url: String, title: String? = null, options: CastOptions = CastOptions()): Boolean =
        suspendOnce { callback -> CoreManager.cast(url, title, options, callback) }

    /**
     * Cast media to specific device
     */
    suspend fun castToDevice(
        device: Device,
        url: String,
        title: String? = null,
        options: CastOptions = CastOptions()
    ): Boolean =
        suspendOnce { callback -> CoreManager.castToDevice(device, url, title, options, callback) }
    
    /**
     * Get current playback progress
     */
    suspend fun getProgress(): Pair<Long, Long>? = 
        suspendWithTriple { callback -> CoreManager.getProgress(callback) }
    
    /**
     * Get volume information
     */
    suspend fun getVolume(): Pair<Int?, Boolean?>? = 
        suspendWithTriple { callback -> CoreManager.getVolume(callback) }
    
    /**
     * Scan local videos on device
     */
    suspend fun scanLocalVideos(context: Context): List<LocalVideo> = 
        suspendOnce { callback -> CoreManager.scanLocalVideos(context, callback) }
    
    /**
     * Cast local file to device
     */
    suspend fun castLocalFile(
        filePath: String,
        device: Device,
        title: String? = null,
        options: CastOptions = CastOptions()
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            var resumed = false
            CoreManager.castLocalFileToDevice(filePath, device, title, options) { success, message ->
                if (!resumed) {
                    resumed = true
                    if (success) {
                        cont.resume(Unit)
                    } else {
                        val exception = when {
                            "file" in message.lowercase() || "not found" in message.lowercase() -> 
                                UPnPException.FileError(message)
                            "network" in message.lowercase() || "connection" in message.lowercase() || "timeout" in message.lowercase() -> 
                                UPnPException.NetworkError(message)
                            "device" in message.lowercase() -> 
                                UPnPException.DeviceError(message)
                            else -> UPnPException.UnknownError(message)
                        }
                        cont.resumeWith(Result.failure(exception))
                    }
                }
            }
        }
    }
    
    /**
     * Get real-time progress (force refresh cache)
     */
    suspend fun getProgressRealtime(): Pair<Long, Long>? = 
        suspendWithTriple { callback -> CoreManager.getProgressRealtime(callback) }
    
    /**
     * Refresh volume cache
     */
    suspend fun refreshVolumeCache(): Boolean = 
        suspendOnce { callback -> CoreManager.refreshVolumeCache(callback) }
    
    /**
     * Refresh progress cache
     */
    suspend fun refreshProgressCache(): Boolean = 
        suspendOnce { callback -> CoreManager.refreshProgressCache(callback) }
    
    /**
     * Initialize DLNA service
     */
    fun init(context: Context) = CoreManager.init(context)
    
    /**
     * Get current casting state
     */
    fun getState(): State = CoreManager.getCurrentState()
    
    /**
     * Clear progress cache (call when switching media)
     */
    fun clearProgressCache() = CoreManager.clearProgressCache()
    
    /**
     * Clean up all resources
     */
    fun cleanup() = CoreManager.cleanup()
} 