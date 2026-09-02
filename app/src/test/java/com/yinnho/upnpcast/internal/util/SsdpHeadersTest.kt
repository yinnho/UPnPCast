package com.yinnho.upnpcast.internal.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SsdpHeadersTest {

    private val mSearchResponse = """
        HTTP/1.1 200 OK
        CACHE-CONTROL: max-age=1800
        LOCATION: http://192.168.1.100:49152/description.xml
        ST: urn:schemas-upnp-org:device:MediaRenderer:1
        USN: uuid:abc-123::urn:schemas-upnp-org:device:MediaRenderer:1
        SERVER: Linux/1.0 UPnP/1.0 Renderer/1.0

    """.trimIndent()

    private val notify = """
        NOTIFY * HTTP/1.1
        HOST: 239.255.255.250:1900
        NTS: ssdp:alive
        NT: urn:schemas-upnp-org:device:MediaRenderer:1
        USN: uuid:abc-123
        LOCATION: http://192.168.1.100:49152/description.xml

    """.trimIndent()

    @Test
    fun parsesHeadersWithLowercaseKeys() {
        val headers = SsdpHeaders.parse(mSearchResponse)

        assertEquals("http://192.168.1.100:49152/description.xml", headers["location"])
        assertEquals("uuid:abc-123::urn:schemas-upnp-org:device:MediaRenderer:1", headers["usn"])
        assertEquals("max-age=1800", headers["cache-control"])
    }

    @Test
    fun parseIgnoresLinesWithoutColon() {
        val headers = SsdpHeaders.parse("HTTP/1.1 200 OK\nUSN: uuid:1\n")
        assertEquals(mapOf("usn" to "uuid:1"), headers)
    }

    @Test
    fun parseTrimsWhitespaceAroundValues() {
        val headers = SsdpHeaders.parse("NTS:   ssdp:alive  \n")
        assertEquals("ssdp:alive", headers["nts"])
    }

    @Test
    fun extractIsCaseInsensitiveOnHeaderName() {
        assertEquals("ssdp:alive", SsdpHeaders.extract(notify, "NTS"))
        assertEquals("ssdp:alive", SsdpHeaders.extract(notify, "nts"))
    }

    @Test
    fun extractReturnsNullForMissingHeader() {
        assertNull(SsdpHeaders.extract(notify, "SERVER"))
    }

    @Test
    fun extractTrimsValue() {
        assertEquals("uuid:abc-123", SsdpHeaders.extract(notify, "USN"))
    }
}
