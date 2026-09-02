package com.yinnho.upnpcast.internal.discovery

import android.util.Log
import com.yinnho.upnpcast.internal.core.ScopeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSDP device discovery service
 */
internal class SsdpDeviceDiscovery(
    private val onDeviceFound: (RemoteDevice) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SSDP-Discovery").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
) {
    private val tag = "SsdpDeviceDiscovery"

    companion object {
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val MULTICAST_PORT = 1900
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val DEVICE_TIMEOUT_MS = 300000L
        private const val MAX_PROCESSED_LOCATIONS = 200
        
        private val SEARCH_TARGETS = arrayOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )
    }

    private val isShutdown = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private val multicastGroup by lazy { InetSocketAddress(MULTICAST_ADDRESS, MULTICAST_PORT) }
    private val descriptionParser = DeviceDescriptionParser()

    private val processedLocations = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_PROCESSED_LOCATIONS
        }
    }
    private val processedLock = Any()
    private val deviceTimeouts = ConcurrentHashMap<String, Long>()

    /**
     * Initialize Socket
     *
     * reuseAddress must be set before binding. Prefer port 1900 so that
     * multicast NOTIFY messages can be received; fall back to an ephemeral
     * port when 1900 is held by another process (unicast M-SEARCH responses
     * still reach an ephemeral source port).
     */
    private fun initializeSocket() {
        if (socket != null) return

        val newSocket = MulticastSocket(null)
        try {
            newSocket.reuseAddress = true
            try {
                newSocket.bind(InetSocketAddress(MULTICAST_PORT))
            } catch (e: BindException) {
                Log.w(tag, "Port $MULTICAST_PORT unavailable, binding ephemeral port (NOTIFY reception disabled)")
                newSocket.bind(InetSocketAddress(0))
            }
            newSocket.timeToLive = 4
            newSocket.joinGroup(multicastGroup, null)
            socket = newSocket
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize socket", e)
            try {
                newSocket.close()
            } catch (ignore: Exception) {
                // Socket already closed
            }
        }
    }

    /**
     * Close Socket
     */
    private fun closeSocket() {
        socket?.let { s ->
            try {
                try {
                    s.leaveGroup(multicastGroup, null)
                } catch (e: Exception) {
                    // Continue with close operation
                }
                s.close()
            } catch (e: Exception) {
                Log.e(tag, "Failed to close socket", e)
            } finally {
                socket = null
            }
        }
    }

    /**
     * Start SSDP device discovery
     *
     * The listener runs on its own daemon thread and is started before the
     * M-SEARCH requests are sent, so no response can arrive while nobody is
     * listening, and repeated searches never queue behind the listener loop.
     */
    fun startSearch() {
        if (isShutdown.get()) return

        initializeSocket()
        startResponseListener()

        executor.execute {
            SEARCH_TARGETS.forEach { target ->
                sendSearchRequest(target)
            }
        }
    }

    /**
     * Send search request
     */
    private fun sendSearchRequest(target: String) {
        if (isShutdown.get()) return

        try {
            val message = """
                M-SEARCH * HTTP/1.1
                HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT
                MAN: "ssdp:discover"
                MX: 3
                ST: $target
                USER-AGENT: UPnPCast/1.0

            """.trimIndent()

            val bytes = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                bytes,
                bytes.size,
                InetAddress.getByName(MULTICAST_ADDRESS),
                MULTICAST_PORT
            )

            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send search request: $target", e)
        }
    }

    /**
     * Start response listener on a dedicated daemon thread
     *
     * Read timeouts are expected while waiting for packets and must not
     * terminate the loop; it exits only on shutdown or a real socket error.
     */
    private fun startResponseListener() {
        if (isShutdown.get()) return
        if (!isListening.compareAndSet(false, true)) return

        val listener = Thread({
            try {
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                socket?.soTimeout = SOCKET_TIMEOUT_MS

                while (!isShutdown.get()) {
                    try {
                        socket?.receive(packet)
                        processResponse(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected between packets; keep listening
                    } catch (e: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Response listener failed", e)
            } finally {
                isListening.set(false)
            }
        }, "SSDP-Listener")
        listener.isDaemon = true
        listener.start()
    }

    /**
     * Process SSDP response
     */
    private fun processResponse(packet: DatagramPacket) {
        try {
            val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val fromAddress = packet.address
            
            when {
                message.startsWith("HTTP/1.1 200 OK", ignoreCase = true) -> {
                    processSsdpResponse(message, fromAddress)
                }
                message.startsWith("NOTIFY", ignoreCase = true) -> {
                    processNotify(message, fromAddress)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to process response", e)
        }
    }

    /**
     * Process NOTIFY message
     */
    private fun processNotify(message: String, fromAddress: InetAddress) {
        try {
            val nts = extractHeader(message, "NTS")
            if (nts == "ssdp:alive") {
                val headers = parseSsdpHeaders(message)
                val location = headers["location"]
                
                if (location != null) {
                    synchronized(processedLock) {
                        if (processedLocations.containsKey(location)) {
                            processedLocations[location] = System.currentTimeMillis()
                            deviceTimeouts[location] = System.currentTimeMillis()
                            return
                        }
                    }
                    
                    processSsdpResponse(message, fromAddress)
                }
            } else if (nts == "ssdp:byebye") {
                val usn = extractHeader(message, "USN")
                if (usn != null) {
                    val headers = parseSsdpHeaders(message)
                    val location = headers["location"]
                    if (location != null) {
                        synchronized(processedLock) {
                            processedLocations.remove(location)
                            deviceTimeouts.remove(location)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to process NOTIFY", e)
        }
    }

    /**
     * Extract HTTP header information
     */
    private fun extractHeader(message: String, headerName: String): String? {
        val regex = "$headerName:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.get(1)?.trim()
    }

    /**
     * Parse SSDP header information
     */
    private fun parseSsdpHeaders(message: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        message.lines().forEach { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    /**
     * Process SSDP response
     */
    private fun processSsdpResponse(response: String, fromAddress: InetAddress) {
        try {
            val headers = parseSsdpHeaders(response)
            val location = headers["location"]
            val usn = headers["usn"]
            
            if (location != null && usn != null) {
                // Check and mark processed
                synchronized(processedLock) {
                    if (processedLocations.containsKey(location)) {
                        return
                    }
                    processedLocations[location] = System.currentTimeMillis()
                    deviceTimeouts[location] = System.currentTimeMillis()
                }
                
                // Asynchronous device description parsing
                ScopeManager.appScope.launch {
                    try {
                        val deviceInfo = descriptionParser.parseDeviceDescription(location)
                        
                        val device = descriptionParser.createEnhancedDevice(
                            id = location,
                            address = fromAddress.hostAddress ?: "unknown",
                            locationUrl = location,
                            deviceInfo = deviceInfo
                        )
                        
                        onDeviceFound(device)
                        
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to process device description: $location", e)
                        
                        // Remove from cache on parsing failure
                        synchronized(processedLock) {
                            processedLocations.remove(location)
                            deviceTimeouts.remove(location)
                        }
                        
                        // Create fallback device for structural errors
                        if (isStructuralError(e)) {
                            val fallbackDevice = createFallbackDevice(location, fromAddress.hostAddress ?: "unknown", location)
                            
                            onDeviceFound(fallbackDevice)
                            
                            synchronized(processedLock) {
                                processedLocations[location] = System.currentTimeMillis()
                                deviceTimeouts[location] = System.currentTimeMillis()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to process SSDP response", e)
        }
    }

    /**
     * Check if it's a structural error
     */
    private fun isStructuralError(exception: Exception): Boolean {
        return when (exception) {
            is java.net.MalformedURLException,
            is java.io.FileNotFoundException,
            is java.net.UnknownHostException -> true
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.io.IOException -> false
            else -> {
                val message = exception.message?.lowercase() ?: ""
                "xml" in message || "parse" in message
            }
        }
    }

    /**
     * Create fallback device information
     */
    private fun createFallbackDevice(deviceId: String, address: String, location: String): RemoteDevice {
        return RemoteDevice(
            id = deviceId,
            displayName = "DLNA device",
            manufacturer = "Unknown",
            address = address,
            details = mapOf(
                "friendlyName" to "DLNA device",
                "manufacturer" to "Unknown",
                "modelName" to "Unknown Model",
                "deviceType" to "Unknown",
                "locationUrl" to location
            )
        )
    }

    /**
     * Clean up timeout devices
     */
    private fun cleanupTimeoutDevices() {
        val currentTime = System.currentTimeMillis()
        val timeoutDevices = mutableListOf<String>()
        
        synchronized(processedLock) {
            deviceTimeouts.entries.removeAll { (location, timestamp) ->
                val isTimeout = currentTime - timestamp > DEVICE_TIMEOUT_MS
                if (isTimeout) {
                    timeoutDevices.add(location)
                    processedLocations.remove(location)
                }
                isTimeout
            }
        }
    }

    /**
     * Shutdown discoverer
     */
    fun shutdown() {
        if (isShutdown.getAndSet(true)) return
        
        try {
            // 协程作用域由ScopeManager统一管理，无需单独取消
        } catch (e: Exception) {
            Log.w(tag, "Failed to cancel search scope", e)
        }
        
        try {
            if (executor is ExecutorService) {
                executor.shutdown()
                
                // 等待任务完成，最多等待5秒
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(tag, "Executor didn't terminate gracefully, forcing shutdown")
                    executor.shutdownNow()
                    
                    // 再等待2秒确保强制关闭完成
                    if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.e(tag, "Executor didn't terminate after force shutdown")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to shutdown executor", e)
        }
        
        closeSocket()
        
        synchronized(processedLock) {
            processedLocations.clear()
            deviceTimeouts.clear()
        }
    }
} 