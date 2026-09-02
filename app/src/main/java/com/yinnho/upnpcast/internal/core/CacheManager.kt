package com.yinnho.upnpcast.internal.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.yinnho.upnpcast.internal.media.DlnaMediaController

/**
 * Cache manager for volume and progress caching.
 * The controller is supplied per call by CoreManager, so cached values always
 * belong to the device it was obtained for.
 */
internal class CacheManager(
    private val coreScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CacheManager"
        private const val VOLUME_CACHE_DURATION = 10000L // 10 seconds volume cache validity period
        private const val PROGRESS_CACHE_DURATION = 5000L // 5 seconds progress cache validity period
    }

    @Volatile
    private var cachedVolume: Int = -1
    @Volatile
    private var cachedMuted: Boolean = false
    @Volatile
    private var lastVolumeUpdate: Long = 0

    @Volatile
    private var cachedCurrentMs: Long = 0L
    @Volatile
    private var cachedTotalMs: Long = 0L
    @Volatile
    private var lastProgressUpdate: Long = 0L
    @Volatile
    private var isPlaying: Boolean = false

    /**
     * Last observed AVTransport state (PLAYING / PAUSED_PLAYBACK / ...),
     * null when never queried. Drives progress interpolation.
     */
    @Volatile
    var cachedTransportState: String? = null
        private set

    @Volatile
    private var volumeRefreshJob: Job? = null
    @Volatile
    private var progressRefreshJob: Job? = null

    /**
     * Get volume with caching; returns null when the device cannot be queried
     */
    suspend fun getVolume(controller: DlnaMediaController?): Pair<Int?, Boolean?>? {
        if (controller == null) return null

        val now = System.currentTimeMillis()
        return if (now - lastVolumeUpdate < VOLUME_CACHE_DURATION && cachedVolume >= 0) {
            refreshVolumeCacheAsync(controller)
            Pair(cachedVolume, cachedMuted)
        } else {
            if (refreshVolumeCache(controller)) Pair(cachedVolume, cachedMuted) else null
        }
    }

    /**
     * Get progress with caching and interpolation; returns null when the
     * device cannot be queried
     */
    suspend fun getProgress(controller: DlnaMediaController?): Pair<Long, Long>? {
        if (controller == null) return null

        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - lastProgressUpdate

        return if (timeSinceLastUpdate < PROGRESS_CACHE_DURATION && cachedTotalMs > 0) {
            val estimatedProgress = if (isPlaying) {
                (cachedCurrentMs + timeSinceLastUpdate).coerceAtMost(cachedTotalMs)
            } else {
                cachedCurrentMs
            }
            refreshProgressCacheAsync(controller)
            Pair(estimatedProgress, cachedTotalMs)
        } else {
            if (refreshProgressCache(controller)) Pair(cachedCurrentMs, cachedTotalMs) else null
        }
    }

    /**
     * Refresh volume cache from device
     */
    suspend fun refreshVolumeCache(controller: DlnaMediaController): Boolean {
        return try {
            val volume = controller.getVolumeAsync()
            val muted = controller.getMuteAsync()

            if (volume != null) {
                updateVolumeCache(volume, muted ?: false)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh volume cache: ${e.message}")
            false
        }
    }

    /**
     * Refresh progress cache from device, fetching position and transport
     * state in parallel
     */
    suspend fun refreshProgressCache(controller: DlnaMediaController): Boolean {
        return try {
            coroutineScope {
                val positionDeferred = async { controller.getPositionInfo() }
                val stateDeferred = async { controller.getTransportInfo() }
                val progressInfo = positionDeferred.await()
                val transportState = stateDeferred.await()

                if (progressInfo != null) {
                    cachedTransportState = transportState
                    updateProgressCache(progressInfo.first, progressInfo.second, System.currentTimeMillis())
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh progress cache: ${e.message}")
            false
        }
    }

    /**
     * Async refresh volume cache (prevent duplicate requests)
     */
    private fun refreshVolumeCacheAsync(controller: DlnaMediaController) {
        if (volumeRefreshJob?.isActive == true) return

        volumeRefreshJob = coreScope.launch {
            try {
                val volume = controller.getVolumeAsync()
                val muted = controller.getMuteAsync()

                if (volume != null) {
                    updateVolumeCache(volume, muted ?: false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh volume cache async: ${e.message}")
            }
        }
    }

    /**
     * Async refresh progress cache (prevent duplicate requests)
     */
    private fun refreshProgressCacheAsync(controller: DlnaMediaController) {
        if (progressRefreshJob?.isActive == true) return

        progressRefreshJob = coreScope.launch {
            try {
                val progressInfo = controller.getPositionInfo()

                if (progressInfo != null) {
                    updateProgressCache(progressInfo.first, progressInfo.second, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh progress cache async: ${e.message}")
            }
        }
    }

    fun updateTransportState(state: String?) {
        cachedTransportState = state
        updatePlayingState()
    }

    private fun updateVolumeCache(volume: Int, muted: Boolean) {
        cachedVolume = volume
        cachedMuted = muted
        lastVolumeUpdate = System.currentTimeMillis()
    }

    private fun updateProgressCache(currentMs: Long, totalMs: Long, timestamp: Long) {
        cachedCurrentMs = currentMs
        cachedTotalMs = totalMs
        lastProgressUpdate = timestamp
        updatePlayingState()
    }

    private fun updatePlayingState() {
        // Interpolate only from a confirmed PLAYING transport state; a
        // paused device must not have its position advanced client-side
        isPlaying = cachedTransportState == "PLAYING"
    }

    /**
     * Clear progress caches (device kept, media changed)
     */
    fun clearProgress() {
        cachedCurrentMs = 0L
        cachedTotalMs = 0L
        lastProgressUpdate = 0L
        isPlaying = false
        cachedTransportState = null
        progressRefreshJob?.cancel()
        progressRefreshJob = null
    }

    /**
     * Clear all cached data
     */
    fun clearAll() {
        cachedVolume = -1
        cachedMuted = false
        lastVolumeUpdate = 0

        clearProgress()

        volumeRefreshJob?.cancel()
        volumeRefreshJob = null
    }

    /**
     * Get current volume state
     */
    fun getVolumeState(): Pair<Int, Boolean> = Pair(cachedVolume, cachedMuted)
}
