package com.yinnho.upnpcast.internal.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MimeTypesTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "video.mp4, video/mp4",
        "video.MP4, video/mp4",
        "video.m4v, video/mp4",
        "movie.mkv, video/x-matroska",
        "clip.webm, video/x-matroska",
        "old.avi, video/x-msvideo",
        "iphone.mov, video/quicktime",
        "stream.ts, video/mp2t",
        "stream.m2ts, video/mp2t",
        "flash.flv, video/x-flv",
        "mobile.3gp, video/3gpp",
        "song.mp3, audio/mpeg",
        "lossless.flac, audio/flac",
        "audio.aac, audio/mp4",
        "audio.m4a, audio/mp4",
        "pcm.wav, audio/x-wav",
        "vorbis.ogg, audio/ogg",
        "vorbis.oga, audio/ogg",
        "subs.srt, text/srt",
        "archive.zip, application/octet-stream",
        "noextension, application/octet-stream"
    )
    fun mapsExtensionsToMimeTypes(fileName: String, expected: String) {
        assertEquals(expected, MimeTypes.fromFileName(fileName))
    }

    @org.junit.jupiter.api.Test
    fun handlesMultiDotFileNames() {
        assertEquals("video/mp4", MimeTypes.fromFileName("my.movie.2024.mp4"))
    }
}
