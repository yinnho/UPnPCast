package com.yinnho.upnpcast.internal.util

import java.util.Locale

/**
 * DLNA time format conversions (HH:MM:SS ↔ milliseconds)
 */
internal object UpnpTime {

    fun parseToMs(timeString: String): Long {
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

    fun format(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
