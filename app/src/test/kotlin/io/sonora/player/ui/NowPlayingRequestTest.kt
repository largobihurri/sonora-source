package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingRequestTest {

    @Test
    fun `no request does nothing`() {
        for (connected in listOf(true, false)) {
            for (hasTrack in listOf(true, false)) {
                assertEquals(NowPlayingRequest.None, nowPlayingRequest(requested = false, connected, hasTrack))
            }
        }
    }

    @Test
    fun `a request opens Now Playing once a track is there`() {
        assertEquals(NowPlayingRequest.Open, nowPlayingRequest(requested = true, connected = true, hasTrack = true))
    }

    @Test
    fun `a request waits while the player is not connected yet`() {
        assertEquals(NowPlayingRequest.Wait, nowPlayingRequest(requested = true, connected = false, hasTrack = false))
    }

    @Test
    fun `a request with a connected player and no track expires instead of opening Now Playing later`() {
        assertEquals(NowPlayingRequest.Expire, nowPlayingRequest(requested = true, connected = true, hasTrack = false))
    }
}
