package com.yinnho.upnpcast.internal.util

import com.yinnho.upnpcast.CastOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataBuilderTest {

    @Test
    fun buildsDefaultVideoMetadataForMp4Url() {
        val metadata = MetadataBuilder.build(title = "Movie", mediaUrl = "http://host/video.mp4")

        assertTrue(metadata.startsWith("<DIDL-Lite"))
        assertTrue(metadata.contains("<dc:title>Movie</dc:title>"))
        assertTrue(metadata.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(metadata.contains("<res protocolInfo=\"http-get:*:video/mp4:*\">http://host/video.mp4</res>"))
        assertFalse(metadata.contains("sec:"))
    }

    @Test
    fun detectsMimeTypesFromUrl() {
        assertEquals("video/x-matroska", MetadataBuilder.detectMimeType("http://h/a.mkv"))
        assertEquals("application/vnd.apple.mpegurl", MetadataBuilder.detectMimeType("http://h/a.m3u8"))
        assertEquals("audio/mpeg", MetadataBuilder.detectMimeType("http://h/a.mp3"))
        assertEquals("video/mp4", MetadataBuilder.detectMimeType("http://h/a.dat"))
    }

    @Test
    fun audioMimeTypeYieldsMusicTrackClass() {
        val metadata = MetadataBuilder.build(title = "Song", mediaUrl = "http://host/song.mp3")
        assertTrue(metadata.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(metadata.contains("audio/mpeg"))
    }

    @Test
    fun mimeTypeOverrideChangesProtocolInfoAndClass() {
        val metadata = MetadataBuilder.build(
            title = "X",
            mediaUrl = "http://host/file.dat",
            options = CastOptions(mimeType = "video/x-msvideo")
        )
        assertTrue(metadata.contains("http-get:*:video/x-msvideo:*"))
        assertTrue(metadata.contains("object.item.videoItem"))
    }

    @Test
    fun upnpClassOverrideIsUsed() {
        val metadata = MetadataBuilder.build(
            title = "X",
            mediaUrl = "http://host/video.mp4",
            options = CastOptions(upnpClass = "object.item.imageItem")
        )
        assertTrue(metadata.contains("<upnp:class>object.item.imageItem</upnp:class>"))
    }

    @Test
    fun episodeLabelIsAppendedToTitle() {
        val metadata = MetadataBuilder.build(title = "Show", episodeLabel = "S01E01", mediaUrl = "http://h/v.mp4")
        assertTrue(metadata.contains("<dc:title>Show - S01E01</dc:title>"))
    }

    @Test
    fun escapesXmlSpecialCharactersInTitle() {
        val metadata = MetadataBuilder.build(title = "A<B> & \"C\" 'D'", mediaUrl = "http://h/v.mp4")
        assertTrue(metadata.contains("<dc:title>A&lt;B&gt; &amp; &quot;C&quot; &apos;D&apos;</dc:title>"))
        assertFalse(metadata.contains("A<B>"))
    }

    @Test
    fun subtitleUriAddsResAndSamsungExtension() {
        val metadata = MetadataBuilder.build(
            title = "Movie",
            mediaUrl = "http://host/video.mp4",
            options = CastOptions(subtitleUri = "http://host/sub.srt")
        )
        assertTrue(metadata.contains("xmlns:sec=\"http://www.samsung.com/sec/\""))
        assertTrue(metadata.contains("<res protocolInfo=\"http-get:*:text/srt:*\">http://host/sub.srt</res>"))
        assertTrue(metadata.contains("<sec:SubtitleUri>http://host/sub.srt</sec:SubtitleUri>"))
    }

    @Test
    fun subtitleMimeTypeOverrideIsReflectedInProtocolInfo() {
        val metadata = MetadataBuilder.build(
            title = "Movie",
            mediaUrl = "http://host/video.mp4",
            options = CastOptions(subtitleUri = "http://host/sub.vtt", subtitleMimeType = "text/vtt")
        )
        assertTrue(metadata.contains("http-get:*:text/vtt:*"))
    }

    @Test
    fun verbatimMetadataOverrideShortCircuitsGeneration() {
        val custom = "<DIDL-Lite><item id=\"custom\"/></DIDL-Lite>"
        val metadata = MetadataBuilder.build(
            title = "Ignored",
            mediaUrl = "http://host/video.mp4",
            options = CastOptions(metadata = custom)
        )
        assertEquals(custom, metadata)
    }
}
