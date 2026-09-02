package com.yinnho.upnpcast.internal.util

import com.yinnho.upnpcast.CastOptions

/**
 * DIDL-Lite metadata construction for AVTransport SetAVTransportURI
 *
 * [CastOptions.metadata] is sent verbatim when provided. Otherwise the
 * metadata is generated, honoring the mimeType/upnpClass overrides and
 * attaching the subtitle resource when [CastOptions.subtitleUri] is set
 * (the Samsung `sec:SubtitleUri` extension is included as well).
 */
internal object MetadataBuilder {

    private fun escapeXmlBasic(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun escapeXmlContent(text: String): String {
        return escapeXmlBasic(text)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun escapeXmlUrl(url: String): String = escapeXmlBasic(url)

    fun detectMimeType(url: String): String = when {
        url.contains(".mp4", ignoreCase = true) -> "video/mp4"
        url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
        url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
        url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
        else -> "video/mp4"
    }

    fun build(
        title: String,
        episodeLabel: String = "",
        mediaUrl: String = "",
        options: CastOptions = CastOptions()
    ): String {
        options.metadata?.let { return it }

        val displayTitle = if (episodeLabel.isNotEmpty()) "$title - $episodeLabel" else title
        val safeDisplayTitle = escapeXmlContent(displayTitle)
        val safeMediaUrl = escapeXmlUrl(mediaUrl)

        val mediaType = options.mimeType ?: detectMimeType(mediaUrl)

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
}
