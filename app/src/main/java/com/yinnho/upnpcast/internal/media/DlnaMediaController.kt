package com.yinnho.upnpcast.internal.media

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.DeviceDescriptionParser
import com.yinnho.upnpcast.internal.util.MetadataBuilder
import com.yinnho.upnpcast.internal.util.SoapXml
import com.yinnho.upnpcast.internal.util.UpnpTime

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
            val setUriSuccess = setMediaUri(mediaUrl, MetadataBuilder.build(title, episodeLabel, mediaUrl, options))
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
                val timeString = UpnpTime.format(positionMs)
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
        parser = SoapXml::parsePositionInfo
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
        parser = { response -> SoapXml.extractValue(response, "CurrentTransportState")?.trim() }
    )
    
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
    suspend fun getVolumeAsync(): Int? = executeRenderingControl("GetVolume", needResponse = true, parser = SoapXml::parseVolume)
    
    /**
     * Get current mute state
     */
    suspend fun getMuteAsync(): Boolean? = executeRenderingControl("GetMute", needResponse = true, parser = SoapXml::parseMute)
    

    
    /**
     * Release resources
     */
    fun release() {
        coroutineScope.cancel()
        isReleased = true
    }
} 