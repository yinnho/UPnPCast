package com.yinnho.upnpcast.internal.localcast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.media.DlnaMediaController
import kotlinx.coroutines.*

/**
 * Local file casting manager
 * Handles local file casting functionality and file server management
 */
internal class LocalCastManager {
    
    companion object {
        private const val TAG = "LocalCastManager"
        
        /**
         * Cast local file to specified device
         */
        fun castLocalFile(
            context: Context,
            filePath: String,
            device: RemoteDevice,
            title: String?,
            options: CastOptions,
            scope: CoroutineScope,
            callback: (success: Boolean, message: String) -> Unit
        ) {
            scope.launch {
                try {
                    val file = java.io.File(filePath)
                    if (!file.exists() || !file.isFile) {
                        withContext(Dispatchers.Main) {
                            callback(false, "File not found: $filePath")
                        }
                        return@launch
                    }
                    
                    if (!file.canRead()) {
                        withContext(Dispatchers.Main) {
                            callback(false, "File cannot be read, please check permissions: $filePath")
                        }
                        return@launch
                    }
                    
                    val fileUrl = try {
                        LocalFileServer.getInstance(context)
                        LocalFileServer.getFileUrl(filePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start local file server: ${e.message}")
                        withContext(Dispatchers.Main) {
                            callback(false, "Failed to start file server: ${e.message}")
                        }
                        return@launch
                    }
                    
                    if (fileUrl == null) {
                        withContext(Dispatchers.Main) {
                            callback(false, "Failed to generate file access URL")
                        }
                        return@launch
                    }
                    
                    Log.i(TAG, "Local file server URL: $fileUrl")
                    
                    val fileName = file.name
                    val mediaTitle = title ?: fileName
                    
                    try {
                        val controller = DlnaMediaController.getController(device)
                        val success = controller.playMediaDirect(fileUrl, mediaTitle, options = options)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                callback(true, "Local file casting successful")
                            } else {
                                callback(false, "Failed to cast to device")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Casting failed: ${e.message}")
                        withContext(Dispatchers.Main) {
                            callback(false, "Failed to cast to device: ${e.message}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Local file casting failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        callback(false, "Local file casting failed: ${e.message}")
                    }
                }
            }
        }
        
        /**
         * Get access URL for local file
         */
        fun getLocalFileUrl(context: Context, filePath: String): String? {
            return try {
                val file = java.io.File(filePath)
                
                if (!file.exists() || !file.isFile) {
                    Log.w(TAG, "File not found: $filePath")
                    return null
                }
                
                LocalFileServer.getInstance(context)
                LocalFileServer.getFileUrl(filePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get local file URL: ${e.message}")
                null
            }
        }
        
        /**
         * Scan local video files on device
         */
        fun scanLocalVideos(context: Context, callback: (videos: List<com.yinnho.upnpcast.DLNACast.LocalVideo>) -> Unit) {
            val scanner = VideoScanner(context)
            scanner.scanLocalVideos(callback)
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
} 