package com.yinnho.upnpcast.internal.media

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.DeviceDescriptionParser

/**
 * DLNA media controller with SOAP-based control implementation
 */
internal class DlnaMediaController(private val device: RemoteDevice) {
    
    private val tag = "DlnaMediaController"
    private val coroutineScope = CoroutineScope(
        Dispatchers.IO + 
        SupervisorJob() + 
        CoroutineName("DlnaController-${device.id}")
    )
    
    companion object {
        private val deviceControllers = ConcurrentHashMap<String, WeakReference<DlnaMediaController>>()
        
        fun getController(device: RemoteDevice): DlnaMediaController {
            cleanupExpiredControllers()
            
            val existingRef = deviceControllers[device.id]
            val existing = existingRef?.get()
            
            return if (existing != null && !existing.isReleased) {
                existing
            } else {
                val newController = DlnaMediaController(device)
                deviceControllers[device.id] = WeakReference(newController)
                newController
            }
        }
        
        fun clearAllControllers() {
            deviceControllers.values.forEach { ref ->
                ref.get()?.release()
            }
            deviceControllers.clear()
        }
        
        /**
         * Clean up expired weak references to prevent memory bloat
         */
        private fun cleanupExpiredControllers() {
            val iterator = deviceControllers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.get() == null) {
                    iterator.remove()
                }
            }
        }
    }
    
    @Volatile
    private var isReleased = false
    
    private fun checkAvailable(): Boolean {
        return !isReleased && coroutineScope.isActive
    }
    
    /**
     * Build service URL for UPnP control
     */
    private fun buildServiceUrl(serviceTypePattern: String, defaultPath: String): String? {
        return try {
            val services = device.details["services"] as? List<*>
            if (services != null) {
                for (service in services) {
                    if (service is DeviceDescriptionParser.ServiceInfo) {
                        if (service.serviceType.contains(serviceTypePattern, ignoreCase = true)) {
                            var controlUrl = service.controlURL
                            
                            if (!controlUrl.startsWith("http://") && !controlUrl.startsWith("https://")) {
                                val location = device.details["location"] as? String
                                if (location != null) {
                                    val url = java.net.URL(location)
                                    val port = url.port.takeIf { it > 0 } ?: 80
                                    val baseUrl = "http://${device.address}:$port"
                                    controlUrl = if (controlUrl.startsWith("/")) {
                                        "$baseUrl$controlUrl"
                                    } else {
                                        "$baseUrl/$controlUrl"
                                    }
                                }
                            }
                            return controlUrl
                        }
                    }
                }
            }
            
            val location = device.details["location"] as? String
            val port = if (location != null) {
                try {
                    val url = java.net.URL(location)
                    url.port.takeIf { it > 0 } ?: 80
                } catch (e: Exception) {
                    return null
                }
            } else {
                return null
            }

            "http://${device.address}:$port/$defaultPath"
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Play media with direct URL and metadata
     */
    suspend fun playMediaDirect(
        mediaUrl: String,
        title: String,
        episodeLabel: String = "",
        positionMs: Long = 0,
        options: CastOptions = CastOptions()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!checkAvailable()) return@withContext false

        try {
            val setUriSuccess = setMediaUri(mediaUrl, createMetadata(title, episodeLabel, mediaUrl, options))
            if (!setUriSuccess) return@withContext false
            
            val playSuccess = control("play")
            if (!playSuccess) return@withContext false
            
            if (positionMs > 0) {
                delay(1000)
                control("seek", positionMs)
            }
            
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to play media: ${e.message}")
            false
        }
    }
    

    
    /**
     * Generic service action executor for SOAP requests
     */
    private suspend fun <T> executeServiceAction(
        serviceType: String,
        serviceNamespace: String,
        defaultPath: String,
        action: String,
        body: String,
        needResponse: Boolean = false,
        parser: ((String) -> T?)? = null
    ): T? = withContext(Dispatchers.IO) {
        if (!checkAvailable()) return@withContext null
        
        try {
            val serviceUrl = buildServiceUrl(serviceType, defaultPath) ?: return@withContext null
            val response = sendGenericSoapRequest(
                url = serviceUrl,
                soapAction = "$serviceNamespace#$action",
                body = body,
                returnResponse = needResponse
            )
            
            when {
                needResponse && parser != null -> {
                    (response as? String)?.let { parser(it) }
                }
                !needResponse -> {
                    @Suppress("UNCHECKED_CAST")
                    (response as? Boolean ?: false) as T?
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to execute $action: ${e.message}")
            null
        }
    }
    

    
    private suspend fun executeAVTransportAction(
        action: String, 
        extraParams: String = ""
    ): Boolean {
        return executeServiceAction<Boolean>(
            serviceType = "AVTransport",
            serviceNamespace = "urn:schemas-upnp-org:service:AVTransport:1",
            defaultPath = "AVTransport/control",
            action = action,
            body = """
                <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>$extraParams
                </u:$action>
            """.trimIndent(),
            needResponse = false
        ) ?: false
    }
    
    /**
     * Universal media control method
     * @param action The action to perform: "play", "pause", "stop", "volume", "mute", "seek"
     * @param value Optional value for actions that require parameters
     * @return Boolean indicating success/failure
     */
    suspend fun control(action: String, value: Any? = null): Boolean {
        return when (action.lowercase()) {
            "play" -> executeAVTransportAction("Play", "\n                        <Speed>1</Speed>")
            "pause" -> executeAVTransportAction("Pause")
            "stop" -> executeAVTransportAction("Stop")
            
            "volume" -> {
                val volumeLevel = when (value) {
                    is Int -> value.coerceIn(0, 100)
                    is Number -> value.toInt().coerceIn(0, 100)
                    is String -> value.toIntOrNull()?.coerceIn(0, 100) ?: return false
                    else -> return false
                }
                executeRenderingControl<Boolean>("SetVolume", "\n                    <DesiredVolume>$volumeLevel</DesiredVolume>") ?: false
            }
            
            "mute" -> {
                val muteState = when (value) {
                    is Boolean -> if (value) "1" else "0"
                    is String -> when (value.lowercase()) {
                        "true", "1", "on" -> "1"
                        "false", "0", "off" -> "0"
                        else -> return false
                    }
                    is Number -> if (value.toInt() != 0) "1" else "0"
                    else -> return false
                }
                executeRenderingControl<Boolean>("SetMute", "\n                    <DesiredMute>$muteState</DesiredMute>") ?: false
            }
            
            "seek" -> {
                val positionMs = when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: return false
                    else -> return false
                }
                val timeString = formatTime(positionMs)
                executeAVTransportAction("Seek", "\n                        <Unit>REL_TIME</Unit>\n                        <Target>${escapeXmlContent(timeString)}</Target>")
            }
            
            else -> false
        }
    }
    

    
    /**
     * Set media URI
     */
    private suspend fun setMediaUri(mediaUrl: String, metadata: String): Boolean = 
        executeServiceAction<Boolean>(
            serviceType = "AVTransport",
            serviceNamespace = "urn:schemas-upnp-org:service:AVTransport:1",
            defaultPath = "AVTransport/control",
            action = "SetAVTransportURI",
            body = """
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeXmlUrl(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData><![CDATA[$metadata]]></CurrentURIMetaData>
                </u:SetAVTransportURI>
            """.trimIndent(),
            needResponse = false
        ) ?: false
    

    

    
    /**
     * Get playback position information
     */
    suspend fun getPositionInfo(): Pair<Long, Long>? = executeServiceAction(
        serviceType = "AVTransport",
        serviceNamespace = "urn:schemas-upnp-org:service:AVTransport:1",
        defaultPath = "AVTransport/control",
        action = "GetPositionInfo",
        body = """
            <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetPositionInfo>
        """.trimIndent(),
        needResponse = true,
        parser = ::parsePositionInfo
    )

    /**
     * Get transport state: PLAYING, PAUSED_PLAYBACK, STOPPED,
     * TRANSITIONING or NO_MEDIA_PRESENT
     */
    suspend fun getTransportInfo(): String? = executeServiceAction(
        serviceType = "AVTransport",
        serviceNamespace = "urn:schemas-upnp-org:service:AVTransport:1",
        defaultPath = "AVTransport/control",
        action = "GetTransportInfo",
        body = """
            <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:GetTransportInfo>
        """.trimIndent(),
        needResponse = true,
        parser = { response -> parseXmlValue(response, "CurrentTransportState") { it.trim() } }
    )
    
    /**
     * Parse playback position information
     */
    private fun parsePositionInfo(response: String): Pair<Long, Long>? {
        try {
            // Simple XML parsing to get RelTime and TrackDuration
            val relTimePattern = "<RelTime>(.*?)</RelTime>".toRegex()
            val durationPattern = "<TrackDuration>(.*?)</TrackDuration>".toRegex()
            
            val relTimeMatch = relTimePattern.find(response)
            val durationMatch = durationPattern.find(response)
            
            val currentTime = relTimeMatch?.groupValues?.get(1)?.let { parseTimeToMs(it) } ?: 0L
            val totalTime = durationMatch?.groupValues?.get(1)?.let { parseTimeToMs(it) } ?: 0L
            
            return Pair(currentTime, totalTime)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse position info: ${e.message}")
            return null
        }
    }
    
    /**
     * Parse time string to milliseconds
     */
    private fun parseTimeToMs(timeString: String): Long {
        try {
            if (timeString == "NOT_IMPLEMENTED" || timeString.isEmpty()) {
                return 0L
            }
            
            val parts = timeString.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLongOrNull() ?: 0L
                val minutes = parts[1].toLongOrNull() ?: 0L
                val seconds = parts[2].toDoubleOrNull() ?: 0.0
                
                return (hours * 3600 + minutes * 60 + seconds).toLong() * 1000
            }
            return 0L
        } catch (e: Exception) {
            return 0L
        }
    }
    
    /**
     * Generic SOAP request - handles both Boolean and String responses
     */
    private suspend fun sendGenericSoapRequest(
        url: String,
        soapAction: String,
        body: String,
        returnResponse: Boolean = true
    ): Any? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val soapEnvelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        $body
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", "UPnPCast/1.0")
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            
            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(soapEnvelope)
                    writer.flush()
                }
            }
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                if (returnResponse) {
                    connection.inputStream.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                            reader.readText()
                        }
                    }
                } else {
                    true
                }
            } else {
                if (returnResponse) null else false
            }
            
        } catch (e: Exception) {
            Log.e(tag, "SOAP request failed: $soapAction, ${e.message}")
            if (returnResponse) null else false
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * Generic XML value parser
     */
    private fun <T> parseXmlValue(
        response: String, 
        tagName: String, 
        converter: (String) -> T?
    ): T? {
        try {
            val pattern = "<$tagName>(.*?)</$tagName>".toRegex()
            val match = pattern.find(response)
            return match?.groupValues?.get(1)?.let { converter(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse $tagName from response: ${e.message}")
            return null
        }
    }
    
    /**
     * Parse volume from response
     */
    private fun parseVolumeFromResponse(response: String): Int? = 
        parseXmlValue(response, "CurrentVolume") { it.toIntOrNull() }
    
    /**
     * Parse mute state from response
     */
    private fun parseMuteFromResponse(response: String): Boolean? = 
        parseXmlValue(response, "CurrentMute") { value ->
            when (value) {
                "1", "true", "True" -> true
                "0", "false", "False" -> false
                else -> null
            }
        }
    
    /**
     * Basic XML escape (common characters)
     */
    private fun escapeXmlBasic(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    
    /**
     * XML escape for content (includes quotes)
     */
    private fun escapeXmlContent(text: String): String {
        return escapeXmlBasic(text)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * XML escape for URLs (basic only)
     */
    private fun escapeXmlUrl(url: String): String = escapeXmlBasic(url)
    
    /**
     * Create DIDL-Lite metadata
     *
     * [CastOptions.metadata] is sent verbatim when provided. Otherwise the
     * metadata is generated, honoring the mimeType/upnpClass overrides and
     * attaching the subtitle resource when [CastOptions.subtitleUri] is set
     * (the Samsung `sec:SubtitleUri` extension is included as well).
     */
    private fun createMetadata(
        title: String,
        episodeLabel: String,
        mediaUrl: String = "",
        options: CastOptions = CastOptions()
    ): String {
        options.metadata?.let { return it }

        val displayTitle = if (episodeLabel.isNotEmpty()) "$title - $episodeLabel" else title
        val safeDisplayTitle = escapeXmlContent(displayTitle)
        val safeMediaUrl = escapeXmlUrl(mediaUrl)

        val mediaType = options.mimeType ?: when {
            mediaUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
            mediaUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            mediaUrl.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            mediaUrl.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
            else -> "video/mp4"
        }

        val upnpClass = options.upnpClass ?: if (mediaType.startsWith("video") || mediaType.contains("mpegurl")) {
            "object.item.videoItem"
        } else {
            "object.item.audioItem.musicTrack"
        }

        val safeSubtitleUri = options.subtitleUri?.let { escapeXmlUrl(it) }
        val subtitleRes = safeSubtitleUri
            ?.let { "\n        <res protocolInfo=\"http-get:*:${options.subtitleMimeType}:*\">$it</res>" }
            ?: ""
        val subtitleElement = safeSubtitleUri
            ?.let { "\n        <sec:SubtitleUri>$it</sec:SubtitleUri>" }
            ?: ""
        val secNamespace = if (safeSubtitleUri != null) " xmlns:sec=\"http://www.samsung.com/sec/\"" else ""

        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"$secNamespace>
    <item id="1" parentID="0" restricted="1">
        <dc:title>$safeDisplayTitle</dc:title>
        <upnp:class>$upnpClass</upnp:class>$subtitleElement
        <res protocolInfo="http-get:*:$mediaType:*">$safeMediaUrl</res>$subtitleRes
    </item>
</DIDL-Lite>"""
    }
    
    /**
     * Time format
     */
    private fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * Execute RenderingControl action - unified method for both set and get operations
     */
    private suspend fun <T> executeRenderingControl(
        action: String,
        extraParams: String = "",
        needResponse: Boolean = false,
        parser: ((String) -> T?)? = null
    ): T? {
        return executeServiceAction(
            serviceType = "RenderingControl",
            serviceNamespace = "urn:schemas-upnp-org:service:RenderingControl:1",
            defaultPath = "RenderingControl/control",
            action = action,
            body = """
                <u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>$extraParams
                </u:$action>
            """.trimIndent(),
            needResponse = needResponse,
            parser = parser
        )
    }
    
    /**
     * Get current volume
     */
    suspend fun getVolumeAsync(): Int? = executeRenderingControl("GetVolume", needResponse = true, parser = ::parseVolumeFromResponse)
    
    /**
     * Get current mute state
     */
    suspend fun getMuteAsync(): Boolean? = executeRenderingControl("GetMute", needResponse = true, parser = ::parseMuteFromResponse)
    

    
    /**
     * Release resources
     */
    fun release() {
        coroutineScope.cancel()
        isReleased = true
    }
} 