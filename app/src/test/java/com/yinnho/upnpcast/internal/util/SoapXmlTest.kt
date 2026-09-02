package com.yinnho.upnpcast.internal.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoapXmlTest {

    private val positionResponse = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
            <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <Track>1</Track>
                    <TrackDuration>01:30:00</TrackDuration>
                    <TrackMetaData>not xml</TrackMetaData>
                    <TrackURI>http://example.com/video.mp4</TrackURI>
                    <RelTime>00:00:45</RelTime>
                    <AbsTime>00:00:45</AbsTime>
                </u:GetPositionInfoResponse>
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    @Test
    fun extractsSimpleTagValue() {
        assertEquals("00:00:45", SoapXml.extractValue(positionResponse, "RelTime"))
    }

    @Test
    fun returnsNullForMissingTag() {
        assertNull(SoapXml.extractValue(positionResponse, "NoSuchTag"))
    }

    @Test
    fun parsePositionInfoReturnsCurrentAndTotal() {
        assertEquals(Pair(45000L, 5400000L), SoapXml.parsePositionInfo(positionResponse))
    }

    @Test
    fun parsePositionInfoDefaultsMissingFieldsToZero() {
        val response = "<u:GetPositionInfoResponse><RelTime>00:00:10</RelTime></u:GetPositionInfoResponse>"
        assertEquals(Pair(10000L, 0L), SoapXml.parsePositionInfo(response))
    }

    @Test
    fun parsePositionInfoTreatsNotImplementedAsZero() {
        val response = "<u:GetPositionInfoResponse>" +
            "<TrackDuration>NOT_IMPLEMENTED</TrackDuration>" +
            "<RelTime>NOT_IMPLEMENTED</RelTime>" +
            "</u:GetPositionInfoResponse>"
        assertEquals(Pair(0L, 0L), SoapXml.parsePositionInfo(response))
    }

    @Test
    fun parseVolumeParsesInteger() {
        assertEquals(30, SoapXml.parseVolume("<CurrentVolume>30</CurrentVolume>"))
        assertEquals(0, SoapXml.parseVolume("<CurrentVolume>0</CurrentVolume>"))
        assertEquals(100, SoapXml.parseVolume("<CurrentVolume>100</CurrentVolume>"))
    }

    @Test
    fun parseVolumeReturnsNullForNonNumeric() {
        assertNull(SoapXml.parseVolume("<CurrentVolume>loud</CurrentVolume>"))
        assertNull(SoapXml.parseVolume("<NoVolume>5</NoVolume>"))
    }

    @Test
    fun parseMuteRecognizesTrueValues() {
        assertTrue(SoapXml.parseMute("<CurrentMute>1</CurrentMute>")!!)
        assertTrue(SoapXml.parseMute("<CurrentMute>true</CurrentMute>")!!)
        assertTrue(SoapXml.parseMute("<CurrentMute>True</CurrentMute>")!!)
    }

    @Test
    fun parseMuteRecognizesFalseValues() {
        assertFalse(SoapXml.parseMute("<CurrentMute>0</CurrentMute>")!!)
        assertFalse(SoapXml.parseMute("<CurrentMute>false</CurrentMute>")!!)
        assertFalse(SoapXml.parseMute("<CurrentMute>False</CurrentMute>")!!)
    }

    @Test
    fun parseMuteReturnsNullForUnknownValue() {
        assertNull(SoapXml.parseMute("<CurrentMute>maybe</CurrentMute>"))
        assertNull(SoapXml.parseMute("<NoMute>1</NoMute>"))
    }
}
