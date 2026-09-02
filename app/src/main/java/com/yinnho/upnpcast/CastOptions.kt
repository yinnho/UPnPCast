package com.yinnho.upnpcast

/**
 * Advanced casting options for customizing how media is presented to the
 * target device.
 *
 * All fields are optional; when unset the library keeps its previous
 * behavior (auto-detected metadata).
 *
 * @param metadata Full DIDL-Lite metadata override. When set, it is sent
 * verbatim as `CurrentURIMetaData` and every other field is ignored.
 * @param subtitleUri HTTP(S) URL of a subtitle file to attach to the cast.
 * @param subtitleMimeType MIME type of [subtitleUri]; defaults to
 * `text/srt`.
 * @param mimeType Overrides the auto-detected media MIME type (e.g.
 * `video/mp4`) used in the `res` protocolInfo.
 * @param upnpClass Overrides the UPnP object class (e.g.
 * `object.item.videoItem`).
 */
data class CastOptions(
    val metadata: String? = null,
    val subtitleUri: String? = null,
    val subtitleMimeType: String = "text/srt",
    val mimeType: String? = null,
    val upnpClass: String? = null
)
