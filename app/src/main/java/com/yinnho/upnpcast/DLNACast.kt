package com.yinnho.upnpcast

import android.content.Context
import com.yinnho.upnpcast.internal.UPnPException
import com.yinnho.upnpcast.internal.core.CoreManager
import com.yinnho.upnpcast.internal.localcast.LocalCastManager

/**
 * Modern UPnP/DLNA casting interface (pure coroutine version)
 * Architecture: DLNACast -> CoreManager -> DlnaMediaController
 *
 * The engine ([CoreManager]) is created by [init] and replaced atomically;
 * before [init] (or after [cleanup]) queries return neutral defaults and
 * [castLocalFile] throws.
 */
object DLNACast {

    @Volatile
    private var engine: CoreManager? = null

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
    suspend fun control(action: MediaAction, value: Any? = null): Boolean =
        engine?.controlMedia(action.value, value) ?: false

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
     *
     * Returns the complete list of devices found within [timeout]; does not
     * resolve early when the first device appears.
     */
    suspend fun search(timeout: Long = 5000): List<Device> =
        engine?.search(timeout) ?: emptyList()

    /**
     * Cast media to best available device
     */
    suspend fun cast(url: String, title: String? = null, options: CastOptions = CastOptions()): Boolean =
        engine?.cast(url, title, options) ?: false

    /**
     * Cast media to specific device
     */
    suspend fun castToDevice(
        device: Device,
        url: String,
        title: String? = null,
        options: CastOptions = CastOptions()
    ): Boolean =
        engine?.castToDevice(device, url, title, options) ?: false

    /**
     * Get current playback progress
     */
    suspend fun getProgress(): Pair<Long, Long>? = engine?.getProgress()

    /**
     * Query the live playback state from the connected device
     * (GetTransportInfo; reflects pause/stop on the device side)
     */
    suspend fun getPlaybackState(): PlaybackState = engine?.getPlaybackState() ?: PlaybackState.IDLE

    /**
     * Get volume information
     */
    suspend fun getVolume(): Pair<Int?, Boolean?>? = engine?.getVolume()

    /**
     * Scan local videos on device
     */
    suspend fun scanLocalVideos(context: Context): List<LocalVideo> =
        LocalCastManager.scanLocalVideos(context)

    /**
     * Cast local file to device
     *
     * @throws UPnPException.FileError the file does not exist or cannot be read
     * @throws UPnPException.NetworkError the local file server could not be started
     * @throws UPnPException.DeviceError the device was not found or rejected the cast
     * @throws UPnPException.UnknownError the library is not initialized
     */
    suspend fun castLocalFile(
        filePath: String,
        device: Device,
        title: String? = null,
        options: CastOptions = CastOptions()
    ) {
        val core = engine ?: throw UPnPException.UnknownError("DLNACast is not initialized")
        core.castLocalFile(filePath, device, title, options)
    }

    /**
     * Get real-time progress (force refresh cache)
     */
    suspend fun getProgressRealtime(): Pair<Long, Long>? = engine?.getProgressRealtime()

    /**
     * Refresh volume cache
     */
    suspend fun refreshVolumeCache(): Boolean = engine?.refreshVolumeCache() ?: false

    /**
     * Refresh progress cache
     */
    suspend fun refreshProgressCache(): Boolean = engine?.refreshProgressCache() ?: false

    /**
     * Initialize DLNA service; replaces any previous engine
     */
    fun init(context: Context) {
        cleanup()
        engine = CoreManager(context.applicationContext)
    }

    /**
     * Get current casting state
     */
    fun getState(): State = engine?.getCurrentState() ?: State(
        isConnected = false,
        currentDevice = null,
        playbackState = PlaybackState.IDLE
    )

    /**
     * Clear progress cache (call when switching media)
     */
    fun clearProgressCache() {
        engine?.clearProgressCache()
    }

    /**
     * Clean up all resources
     */
    fun cleanup() {
        engine?.shutdown()
        engine = null
    }
}
