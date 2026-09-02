package com.yinnho.upnpcast.internal.util

/**
 * Minimal regex-based extraction of values from SOAP action responses
 */
internal object SoapXml {

    fun extractValue(response: String, tagName: String): String? {
        return try {
            val pattern = "<$tagName>(.*?)</$tagName>".toRegex()
            pattern.find(response)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse GetPositionInfo response into (currentMs, totalMs); missing
     * fields default to 0
     */
    fun parsePositionInfo(response: String): Pair<Long, Long>? {
        return try {
            val currentTime = extractValue(response, "RelTime")?.let { UpnpTime.parseToMs(it) } ?: 0L
            val totalTime = extractValue(response, "TrackDuration")?.let { UpnpTime.parseToMs(it) } ?: 0L
            Pair(currentTime, totalTime)
        } catch (e: Exception) {
            null
        }
    }

    fun parseVolume(response: String): Int? =
        extractValue(response, "CurrentVolume")?.toIntOrNull()

    fun parseMute(response: String): Boolean? =
        extractValue(response, "CurrentMute")?.let { value ->
            when (value) {
                "1", "true", "True" -> true
                "0", "false", "False" -> false
                else -> null
            }
        }
}
