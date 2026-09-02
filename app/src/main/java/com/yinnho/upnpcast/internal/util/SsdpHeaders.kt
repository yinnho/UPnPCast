package com.yinnho.upnpcast.internal.util

/**
 * SSDP message header parsing
 */
internal object SsdpHeaders {

    fun parse(message: String): Map<String, String> {
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

    fun extract(message: String, headerName: String): String? {
        val regex = "$headerName:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.get(1)?.trim()
    }
}
