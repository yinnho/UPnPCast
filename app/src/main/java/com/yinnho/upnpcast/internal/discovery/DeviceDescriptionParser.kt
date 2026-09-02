package com.yinnho.upnpcast.internal.discovery

import android.util.Log
import com.yinnho.upnpcast.internal.core.UpnpHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UPnP device description parser
 */
internal class DeviceDescriptionParser {
    companion object {
        private const val TAG = "DeviceDescriptionParser"
        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 10000
        private const val CACHE_MAX_SIZE = 50
        
        private val SERVICE_PATTERN = "<service>(.*?)</service>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        
        private val deviceCache = object : LinkedHashMap<String, DeviceInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeviceInfo>?): Boolean {
                return size > CACHE_MAX_SIZE
            }
        }
    }
    
    data class DeviceInfo(
        val friendlyName: String,
        val manufacturer: String,
        val modelName: String,
        val deviceType: String,
        val services: List<ServiceInfo> = emptyList()
    )
    
    /**
     * Parse device description from location URL
     */
    suspend fun parseDeviceDescription(locationUrl: String): DeviceInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val cachedInfo = synchronized(deviceCache) {
                    deviceCache[locationUrl]
                }
                
                if (cachedInfo != null) {
                    return@withContext cachedInfo
                }
                
                val xmlContent = downloadXmlContent(locationUrl)
                if (xmlContent.isEmpty()) {
                    return@withContext null
                }
                
                val deviceInfo = parseXmlContent(xmlContent)
                
                if (deviceInfo != null) {
                    synchronized(deviceCache) {
                        deviceCache[locationUrl] = deviceInfo
                    }
                }
                
                deviceInfo
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse device description: $locationUrl", e)
                null
            }
        }
    }
    
    /**
     * Download XML content
     */
    private suspend fun downloadXmlContent(locationUrl: String): String {
        return UpnpHttp.get(
            url = locationUrl,
            connectTimeoutMs = CONNECT_TIMEOUT,
            readTimeoutMs = READ_TIMEOUT,
            maxRetries = 3
        ) ?: ""
    }
    
    /**
     * Parse XML content
     */
    private fun parseXmlContent(xmlContent: String): DeviceInfo? {
        try {
            val friendlyName = extractXmlValue(xmlContent, "friendlyName") ?: "DLNA Device"
            val manufacturer = extractXmlValue(xmlContent, "manufacturer") ?: "Unknown"
            val modelName = extractXmlValue(xmlContent, "modelName") ?: "Unknown Model"
            val deviceType = extractXmlValue(xmlContent, "deviceType") ?: "Unknown"
            
            val services = parseServices(xmlContent)
            
            return DeviceInfo(
                friendlyName = friendlyName.trim(),
                manufacturer = manufacturer.trim(),
                modelName = modelName.trim(),
                deviceType = deviceType.trim(),
                services = services
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML content", e)
            return null
        }
    }
    
    /**
     * Parse service list
     */
    private fun parseServices(xmlContent: String): List<ServiceInfo> {
        val services = mutableListOf<ServiceInfo>()
        
        try {
            val serviceMatches = SERVICE_PATTERN.findAll(xmlContent)
            
            for (serviceMatch in serviceMatches) {
                val serviceXml = serviceMatch.groupValues[1]
                
                val serviceType = extractXmlValue(serviceXml, "serviceType") ?: continue
                val serviceId = extractXmlValue(serviceXml, "serviceId") ?: ""
                val controlURL = extractXmlValue(serviceXml, "controlURL") ?: ""
                val eventSubURL = extractXmlValue(serviceXml, "eventSubURL") ?: ""
                val descriptorURL = extractXmlValue(serviceXml, "SCPDURL") ?: ""
                
                if (serviceType.contains("AVTransport", ignoreCase = true) ||
                    serviceType.contains("RenderingControl", ignoreCase = true) ||
                    serviceType.contains("ConnectionManager", ignoreCase = true)) {
                    
                    services.add(ServiceInfo(
                        serviceType = serviceType,
                        serviceId = serviceId,
                        controlURL = controlURL,
                        eventSubURL = eventSubURL,
                        descriptorURL = descriptorURL
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse service list", e)
        }
        
        return services
    }
    
    /**
     * Extract XML tag value
     */
    private fun extractXmlValue(xmlContent: String, tagName: String): String? {
        try {
            val startTag = "<$tagName"
            val endTag = "</$tagName>"
            
            val startIndex = xmlContent.indexOf(startTag, ignoreCase = true)
            if (startIndex == -1) return null
            
            val tagEndIndex = xmlContent.indexOf('>', startIndex)
            if (tagEndIndex == -1) return null
            
            val endIndex = xmlContent.indexOf(endTag, tagEndIndex, ignoreCase = true)
            if (endIndex == -1) return null
            
            val content = xmlContent.substring(tagEndIndex + 1, endIndex).trim()
            return if (content.isNotBlank()) content else null
            
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Create enhanced RemoteDevice
     */
    fun createEnhancedDevice(
        id: String,
        address: String,
        locationUrl: String,
        deviceInfo: DeviceInfo?
    ): RemoteDevice {
        val info = deviceInfo ?: DeviceInfo("DLNA Device", "Unknown", "Unknown Model", "Unknown")

        return RemoteDevice(
            id = id,
            displayName = info.friendlyName,
            manufacturer = info.manufacturer,
            address = address,
            model = info.modelName,
            locationUrl = locationUrl,
            services = info.services
        )
    }
} 