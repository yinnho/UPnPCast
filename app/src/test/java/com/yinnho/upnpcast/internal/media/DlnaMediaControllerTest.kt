package com.yinnho.upnpcast.internal.media

import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the SOAP control flow against an in-process fake renderer —
 * covers request building, URL resolution and response parsing end to end
 */
class DlnaMediaControllerTest {

    private lateinit var renderer: FakeDlnaRenderer
    private lateinit var controller: DlnaMediaController

    @BeforeEach
    fun setUp() {
        renderer = FakeDlnaRenderer().startServer()
        controller = DlnaMediaController(deviceFor(renderer))
    }

    @AfterEach
    fun tearDown() {
        controller.release()
        renderer.stop()
    }

    private fun deviceFor(renderer: FakeDlnaRenderer): RemoteDevice = RemoteDevice(
        id = renderer.descriptionUrl,
        displayName = "Test Renderer",
        address = "127.0.0.1",
        manufacturer = "UnitTest Corp",
        model = "TestModel 1000",
        locationUrl = renderer.descriptionUrl,
        services = listOf(
            ServiceInfo(
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                serviceId = "AVTransport",
                controlURL = "/control/AVTransport",
                eventSubURL = "/event/AVTransport",
                descriptorURL = "/scpd/AVTransport.xml"
            ),
            ServiceInfo(
                serviceType = "urn:schemas-upnp-org:service:RenderingControl:1",
                serviceId = "RenderingControl",
                controlURL = "/control/RenderingControl",
                eventSubURL = "/event/RenderingControl",
                descriptorURL = "/scpd/RenderingControl.xml"
            )
        )
    )

    @Test
    fun getTransportInfoQueriesRenderer() = runBlocking {
        assertEquals("PLAYING", controller.getTransportInfo())
        assertEquals(1, renderer.callsFor("GetTransportInfo").size)
    }

    @Test
    fun getPositionInfoParsesRelTimeAndDuration() = runBlocking {
        assertEquals(Pair(10000L, 600000L), controller.getPositionInfo())
    }

    @Test
    fun volumeAndMuteAreParsed() = runBlocking {
        assertEquals(30, controller.getVolumeAsync())
        assertEquals(false, controller.getMuteAsync())
    }

    @Test
    fun playSendsPlayAction() = runBlocking {
        assertTrue(controller.control("play"))
        assertEquals(1, renderer.callsFor("Play").size)
        assertTrue(renderer.callsFor("Play").single().body.contains("<Speed>1</Speed>"))
    }

    @Test
    fun seekFormatsTargetAsColonTime() = runBlocking {
        assertTrue(controller.control("seek", 90000L))
        val body = renderer.callsFor("Seek").single().body
        assertTrue(body.contains("<Unit>REL_TIME</Unit>"))
        assertTrue(body.contains("<Target>00:01:30</Target>"))
    }

    @Test
    fun volumeIsCoercedIntoRange() = runBlocking {
        assertTrue(controller.control("volume", 150))
        assertTrue(renderer.callsFor("SetVolume").single().body.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test
    fun muteMapsBooleanState() = runBlocking {
        assertTrue(controller.control("mute", true))
        assertTrue(renderer.callsFor("SetMute").single().body.contains("<DesiredMute>1</DesiredMute>"))
    }

    @Test
    fun playMediaDirectSetsUriThenPlays() = runBlocking {
        assertTrue(controller.playMediaDirect("http://media.example.com/movie.mp4", "Movie"))

        val actions = renderer.soapCalls.map { it.action }
        assertEquals(listOf("SetAVTransportURI", "Play"), actions)

        val setUri = renderer.callsFor("SetAVTransportURI").single().body
        assertTrue(setUri.contains("<CurrentURI>http://media.example.com/movie.mp4</CurrentURI>"))
        assertTrue(setUri.contains("<dc:title>Movie</dc:title>"))
    }

    @Test
    fun playMediaDirectForwardsPositionAsSeek() = runBlocking {
        assertTrue(controller.playMediaDirect("http://m/v.mp4", "T", positionMs = 30000))
        assertEquals(listOf("SetAVTransportURI", "Play", "Seek"), renderer.soapCalls.map { it.action })
        assertTrue(renderer.callsFor("Seek").single().body.contains("<Target>00:00:30</Target>"))
    }

    @Test
    fun deviceErrorsSurfaceAsFailure() = runBlocking {
        renderer.failControlRequests = true

        assertFalse(controller.control("play"))
        assertNull(controller.getTransportInfo())
        assertNull(controller.getVolumeAsync())
    }

    @Test
    fun unknownActionFailsWithoutHttpRequest() = runBlocking {
        assertFalse(controller.control("dance"))
        assertTrue(renderer.soapCalls.isEmpty())
    }

    @Test
    fun releasedControllerRejectsRequests() = runBlocking {
        controller.release()
        assertFalse(controller.control("play"))
        assertNull(controller.getTransportInfo())
    }
}
