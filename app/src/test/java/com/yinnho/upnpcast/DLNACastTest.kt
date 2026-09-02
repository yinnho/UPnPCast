package com.yinnho.upnpcast

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Facade lifecycle: neutral defaults before init, graceful behavior with a
 * context that has no WifiManager (unit test environment).
 *
 * All assertions live in one method because DLNACast is a singleton —
 * test order inside the method is the only reliable ordering.
 */
class DLNACastTest {

    @Test
    fun facadeLifecycle() = runBlocking {
        DLNACast.cleanup()

        // Not initialized: neutral defaults, no hangs
        assertEquals(DLNACast.PlaybackState.IDLE, DLNACast.getPlaybackState())
        assertTrue(DLNACast.search(100).isEmpty())
        assertFalse(DLNACast.play())
        assertFalse(DLNACast.pause())
        assertFalse(DLNACast.setVolume(50))
        assertFalse(DLNACast.cast("http://media/movie.mp4", "T"))
        assertNull(DLNACast.getProgress())
        assertNull(DLNACast.getVolume())
        assertFalse(DLNACast.refreshVolumeCache())

        val state = DLNACast.getState()
        assertFalse(state.isConnected)
        assertNull(state.currentDevice)
        assertEquals(DLNACast.PlaybackState.IDLE, state.playbackState)

        val notInitialized = assertThrows(com.yinnho.upnpcast.internal.UPnPException.UnknownError::class.java) {
            runBlocking {
                DLNACast.castLocalFile("/no/such/file.mp4", DLNACast.Device("id", "n", "a", false))
            }
        }
        assertTrue(notInitialized.message!!.contains("not initialized"))

        // Initialized with a WifiManager-less context: engine comes up
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        DLNACast.init(context)

        val initializedState = DLNACast.getState()
        assertFalse(initializedState.isConnected)
        assertEquals(DLNACast.PlaybackState.IDLE, initializedState.playbackState)

        // Unknown device fails fast with false, not an exception
        assertFalse(DLNACast.castToDevice(DLNACast.Device("nope", "n", "a", false), "http://m/v.mp4", "T"))
        assertFalse(DLNACast.control(DLNACast.MediaAction.PLAY))

        // cleanup restores the neutral state
        DLNACast.cleanup()
        assertEquals(DLNACast.PlaybackState.IDLE, DLNACast.getPlaybackState())
        assertFalse(DLNACast.getState().isConnected)
    }

    @Test
    fun scanLocalVideosWithoutPermissionReturnsEmptyList() = runBlocking {
        // The mocked context's ContentResolver is null; the scanner must
        // degrade to an empty result rather than crash
        val context = Mockito.mock(Context::class.java)
        val videos = DLNACast.scanLocalVideos(context)
        assertTrue(videos.isEmpty())
    }
}
