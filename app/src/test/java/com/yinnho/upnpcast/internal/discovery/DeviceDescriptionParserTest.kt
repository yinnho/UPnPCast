package com.yinnho.upnpcast.internal.discovery

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Parses a real device description document fetched over HTTP from an
 * in-process fake renderer
 */
class DeviceDescriptionParserTest {

    private lateinit var renderer: com.yinnho.upnpcast.internal.media.FakeDlnaRenderer
    private val parser = DeviceDescriptionParser()

    @BeforeEach
    fun setUp() {
        renderer = com.yinnho.upnpcast.internal.media.FakeDlnaRenderer().startServer()
    }

    @AfterEach
    fun tearDown() {
        renderer.stop()
    }

    @Test
    fun parsesDescriptionOverHttp() = runBlocking {
        val info = parser.parseDeviceDescription(renderer.descriptionUrl)

        assertEquals("Test Renderer", info?.friendlyName)
        assertEquals("UnitTest Corp", info?.manufacturer)
        assertEquals("TestModel 1000", info?.modelName)
        assertEquals(2, info?.services?.size)

        val avTransport = info?.services?.first { it.serviceType.contains("AVTransport") }
        assertEquals("/control/AVTransport", avTransport?.controlURL)
    }

    @Test
    fun createEnhancedDeviceMapsTypedFields() {
        val device = parser.createEnhancedDevice(
            id = renderer.descriptionUrl,
            address = "127.0.0.1",
            locationUrl = renderer.descriptionUrl,
            deviceInfo = DeviceDescriptionParser.DeviceInfo(
                friendlyName = "F",
                manufacturer = "M",
                modelName = "MM",
                deviceType = "T",
                services = listOf(
                    ServiceInfo("urn:schemas-upnp-org:service:AVTransport:1", "id", "/c", "/e", "/s")
                )
            )
        )

        assertEquals("F", device.displayName)
        assertEquals("M", device.manufacturer)
        assertEquals("MM", device.model)
        assertEquals(renderer.descriptionUrl, device.locationUrl)
        assertEquals(1, device.services.size)
        assertEquals("/c", device.services.single().controlURL)
    }

    @Test
    fun createEnhancedDeviceFallsBackToDefaults() {
        val device = parser.createEnhancedDevice(
            id = "id",
            address = "1.2.3.4",
            locationUrl = "http://1.2.3.4/desc",
            deviceInfo = null
        )

        assertEquals("DLNA Device", device.displayName)
        assertEquals("Unknown", device.manufacturer)
        assertTrue(device.services.isEmpty())
    }

    @Test
    fun unreachableDescriptionReturnsNull() = runBlocking {
        // 404 path retries 3 times before giving up (~3s of backoff)
        assertNull(parser.parseDeviceDescription("${renderer.baseUrl}/nope"))
    }
}
