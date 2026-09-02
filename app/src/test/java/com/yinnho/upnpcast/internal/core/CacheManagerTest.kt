package com.yinnho.upnpcast.internal.core

import com.yinnho.upnpcast.internal.discovery.RemoteDevice
import com.yinnho.upnpcast.internal.discovery.ServiceInfo
import com.yinnho.upnpcast.internal.media.DlnaMediaController
import com.yinnho.upnpcast.internal.media.FakeDlnaRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CacheManagerTest {

    private lateinit var renderer: FakeDlnaRenderer
    private lateinit var controller: DlnaMediaController
    private lateinit var scope: CoroutineScope
    private lateinit var cacheManager: CacheManager

    @BeforeEach
    fun setUp() {
        renderer = FakeDlnaRenderer().startServer()
        controller = DlnaMediaController(deviceFor(renderer))
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cacheManager = CacheManager(scope)
    }

    @AfterEach
    fun tearDown() {
        cacheManager.clearAll()
        scope.cancel()
        controller.release()
        renderer.stop()
    }

    private fun deviceFor(renderer: FakeDlnaRenderer) = RemoteDevice(
        id = renderer.descriptionUrl,
        displayName = "Test Renderer",
        address = "127.0.0.1",
        locationUrl = renderer.descriptionUrl,
        services = listOf(
            ServiceInfo("urn:schemas-upnp-org:service:AVTransport:1", "a", "/control/AVTransport", "/e", "/s"),
            ServiceInfo("urn:schemas-upnp-org:service:RenderingControl:1", "r", "/control/RenderingControl", "/e", "/s")
        )
    )

    @Test
    fun queriesWithoutControllerReturnNull() = runBlocking {
        assertNull(cacheManager.getProgress(null))
        assertNull(cacheManager.getVolume(null))
    }

    @Test
    fun progressIsFetchedAndThenServedFromCache() = runBlocking {
        val first = cacheManager.getProgress(controller)
        assertEquals(Pair(10000L, 600000L), first)

        val positionCallsAfterFirst = renderer.callsFor("GetPositionInfo").size
        val second = cacheManager.getProgress(controller)
        assertNotNull(second)
        assertEquals(positionCallsAfterFirst, renderer.callsFor("GetPositionInfo").size)
    }

    @Test
    fun progressInterpolatesOnlyWhilePlaying() = runBlocking {
        assertTrue(cacheManager.refreshProgressCache(controller))
        assertEquals("PLAYING", cacheManager.cachedTransportState)

        // Second read inside the cache window advances the position
        val interpolated = cacheManager.getProgress(controller)!!
        assertTrue(interpolated.first >= 10000L)
        assertEquals(600000L, interpolated.second)

        // A paused device must not advance
        renderer.transportState = "PAUSED_PLAYBACK"
        assertTrue(cacheManager.refreshProgressCache(controller))
        val paused = cacheManager.getProgress(controller)!!
        assertTrue(paused.first < 11000L)
    }

    @Test
    fun volumeIsCachedWithMuteState() = runBlocking {
        assertEquals(Pair(30, false), cacheManager.getVolume(controller))

        val volumeCallsAfterFirst = renderer.callsFor("GetVolume").size
        assertEquals(Pair(30, false), cacheManager.getVolume(controller))
        assertEquals(volumeCallsAfterFirst, renderer.callsFor("GetVolume").size)
    }

    @Test
    fun clearAllResetsCachedState() = runBlocking {
        cacheManager.refreshProgressCache(controller)
        cacheManager.refreshVolumeCache(controller)

        cacheManager.clearAll()

        assertNull(cacheManager.cachedTransportState)
        assertEquals(-1, cacheManager.getVolumeState().first)
    }
}
