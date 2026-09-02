package com.yinnho.upnpcast.internal.localcast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.internal.UPnPException
import com.yinnho.upnpcast.internal.media.DlnaMediaController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Local file casting manager
 * Handles local file casting functionality and file server management
 */
internal object LocalCastManager {

    private const val TAG = "LocalCastManager"

    /**
     * Cast a local file to the device controlled by [controller].
     *
     * @throws UPnPException.FileError the file does not exist or cannot be read
     * @throws UPnPException.NetworkError the local file server could not be started
     * @throws UPnPException.DeviceError the device rejected the cast request
     */
    suspend fun castLocalFile(
        context: Context,
        filePath: String,
        controller: DlnaMediaController,
        title: String?,
        options: CastOptions
    ) {
        val file = java.io.File(filePath)
        if (!file.exists() || !file.isFile) {
            throw UPnPException.FileError("File not found: $filePath")
        }
        if (!file.canRead()) {
            throw UPnPException.FileError("File cannot be read, please check permissions: $filePath")
        }

        val fileUrl = try {
            LocalFileServer.getInstance(context)
            LocalFileServer.getFileUrl(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local file server: ${e.message}")
            throw UPnPException.NetworkError("Failed to start file server: ${e.message}", e)
        } ?: throw UPnPException.NetworkError("Failed to generate file access URL")

        Log.i(TAG, "Local file server URL: $fileUrl")

        val mediaTitle = title ?: file.name
        val success = try {
            controller.playMediaDirect(fileUrl, mediaTitle, options = options)
        } catch (e: Exception) {
            Log.e(TAG, "Casting failed: ${e.message}")
            throw UPnPException.DeviceError("Failed to cast to device: ${e.message}", e)
        }
        if (!success) {
            throw UPnPException.DeviceError("Device rejected the cast request")
        }
    }

    /**
     * Scan local video files on device
     */
    suspend fun scanLocalVideos(context: Context): List<DLNACast.LocalVideo> =
        suspendCancellableCoroutine { continuation ->
            VideoScanner(context).scanLocalVideos { videos ->
                if (continuation.isActive) continuation.resume(videos)
            }
        }

    /**
     * Clean up local casting resources
     */
    fun cleanup() {
        try {
            LocalFileServer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing local file server: ${e.message}")
        }
    }
}
