package com.yinnho.upnpcast.internal.util

/**
 * Resolve MIME type from the file extension; some TVs reject or
 * mis-handle unknown content types.
 */
internal object MimeTypes {

    fun fromFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv", "webm" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "aac", "m4a" -> "audio/mp4"
            "wav" -> "audio/x-wav"
            "ogg", "oga" -> "audio/ogg"
            "srt" -> "text/srt"
            else -> "application/octet-stream"
        }
    }
}
