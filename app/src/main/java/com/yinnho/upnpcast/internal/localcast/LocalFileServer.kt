package com.yinnho.upnpcast.internal.localcast

import android.content.Context
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import kotlin.random.Random
import com.yinnho.upnpcast.internal.util.MimeTypes

/**
 * Local file server based on NanoHTTPD
 * Supports Range requests, UTF-8 paths, and large file transmission
 */
internal class LocalFileServer private constructor(
    context: Context,
    port: Int
) : NanoHTTPD(port) {
    
    private val contextRef = WeakReference(context.applicationContext)
    
    val context: Context? get() = contextRef.get()
    
    companion object {
        private const val TAG = "LocalFileServer"
        private const val DEFAULT_PORT_START = 8081
        private const val DEFAULT_PORT_END = 8090
        
        @Volatile
        private var instance: LocalFileServer? = null
        private const val MAX_REGISTRY_ENTRIES = 64

        // token -> filePath, LRU-bounded: every cast generates a new token
        // and an unbounded map would grow for the process lifetime
        private val fileRegistry = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_REGISTRY_ENTRIES
            }
        }

        private fun putToken(token: String, filePath: String) =
            synchronized(fileRegistry) { fileRegistry[token] = filePath }

        private fun takeToken(token: String): String? =
            synchronized(fileRegistry) { fileRegistry[token] }

        private fun removeToken(token: String) =
            synchronized(fileRegistry) { fileRegistry.remove(token) }
        
        /**
         * Get server instance (singleton)
         */
        fun getInstance(context: Context): LocalFileServer {
            return instance ?: synchronized(this) {
                instance ?: createServer(context.applicationContext).also { instance = it }
            }
        }
        
        private fun createServer(context: Context): LocalFileServer {
            var port = DEFAULT_PORT_START
            var server: LocalFileServer? = null
            
            while (port <= DEFAULT_PORT_END && server == null) {
                try {
                    val testServer = LocalFileServer(context, port)
                    testServer.start()
                    server = testServer
                    Log.i(TAG, "Local file server started on port: $port")
                } catch (e: Exception) {
                    Log.w(TAG, "Port $port is occupied, trying next port")
                    port++
                }
            }
            
            return server ?: throw RuntimeException("Unable to start local file server on any port in range $DEFAULT_PORT_START-$DEFAULT_PORT_END")
        }
        
        /**
         * Get file access URL with unique token
         */
        fun getFileUrl(filePath: String): String? {
            return try {
                val context = instance?.context ?: return null
                val server = getInstance(context)
                
                val token = generateToken()
                putToken(token, filePath)
                
                val serverAddress = getLocalIpAddress()
                "http://$serverAddress:${server.listeningPort}/file/$token"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file URL: ${e.message}")
                null
            }
        }
        
        /**
         * Release server resources
         */
        fun release() {
            try {
                instance?.stop()
                instance = null
                synchronized(fileRegistry) { fileRegistry.clear() }
                Log.i(TAG, "Local file server stopped and resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing server: ${e.message}")
            }
        }
        
        private fun getLocalIpAddress(): String {
            return try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (networkInterface in interfaces) {
                    if (!networkInterface.isLoopback && networkInterface.isUp) {
                        val addresses = networkInterface.inetAddresses
                        for (address in addresses) {
                            if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                                return address.hostAddress ?: "127.0.0.1"
                            }
                        }
                    }
                }
                "127.0.0.1"
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get local IP, using localhost")
                "127.0.0.1"
            }
        }
        
        private fun generateToken(): String {
            val timestamp = System.currentTimeMillis()
            val random = Random.nextInt(10000, 99999)
            return Base64.encodeToString("${timestamp}_$random".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        }
    }
    
    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            Log.d(TAG, "Serving request: $uri")
            
            when {
                uri.startsWith("/file/") -> serveFile(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving request: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal server error")
        }
    }
    
    private fun serveFile(session: IHTTPSession): Response {
        val uri = session.uri
        val token = uri.substring("/file/".length)
        
        val filePath = takeToken(token)
        if (filePath == null) {
            Log.w(TAG, "Invalid token: $token")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "File not found: $filePath")
            removeToken(token)
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        
        return try {
            serveFileWithRange(session, file)
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: $filePath, ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading file")
        }
    }
    
    /**
     * File serving with Range request support for streaming
     */
    private fun serveFileWithRange(session: IHTTPSession, file: File): Response {
        val fileSize = file.length()
        var rangeStart = 0L
        var rangeEnd = fileSize - 1

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                val range = rangeHeader.substring(6)
                val parts = range.split("-")
                if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                    rangeStart = parts[0].toLong()
                }
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    rangeEnd = parts[1].toLong()
                }

                // Ensure range is valid
                rangeStart = rangeStart.coerceAtLeast(0)
                rangeEnd = rangeEnd.coerceAtMost(fileSize - 1)

                if (rangeStart > rangeEnd) {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Range not satisfiable"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid range header: $rangeHeader")
            }
        }

        val contentLength = rangeEnd - rangeStart + 1
        val inputStream = FileInputStream(file)
        // skip() may skip fewer bytes than requested; loop until the
        // requested offset is reached or EOF, otherwise the stream starts
        // at the wrong position and the TV receives a corrupt stream
        var remaining = rangeStart
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }

        val response = newFixedLengthResponse(
            if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            MimeTypes.fromFileName(file.name),
            inputStream,
            contentLength
        )

        // Add necessary response headers
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", contentLength.toString())

        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileSize")
        }

        // Add CORS headers support
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range")

        Log.d(TAG, "Serving file: ${file.name}, range: $rangeStart-$rangeEnd/$fileSize")
        return response
    }
}