package io.sonora.player.ui

import io.sonora.core.domain.model.NowPlayingRotation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatedNowPlayingTest {

    @Test
    fun `with the Album Cover Flow choice, an open Now Playing turned sideways shows the albums Cover Flow`() {
        assertTrue(showsAlbumCoverFlow(NowPlayingRotation.ALBUM_COVER_FLOW, landscape = true, nowPlayingOpen = true, dismissed = false))
    }

    @Test
    fun `the default choice keeps the landscape Now Playing`() {
        assertFalse(showsAlbumCoverFlow(NowPlayingRotation.NOW_PLAYING_LANDSCAPE, landscape = true, nowPlayingOpen = true, dismissed = false))
    }

    @Test
    fun `upright, with Now Playing closed, or after its Now Playing button, Now Playing shows itself`() {
        val choice = NowPlayingRotation.ALBUM_COVER_FLOW
        assertFalse(showsAlbumCoverFlow(choice, landscape = false, nowPlayingOpen = true, dismissed = false))
        assertFalse(showsAlbumCoverFlow(choice, landscape = true, nowPlayingOpen = false, dismissed = false))
        assertFalse(showsAlbumCoverFlow(choice, landscape = true, nowPlayingOpen = true, dismissed = true))
    }

    @Test
    fun `gravity mode never covers its level Now Playing with the albums Cover Flow`() {
        for (landscape in listOf(true, false)) {
            for (dismissed in listOf(true, false)) {
                assertFalse(showsAlbumCoverFlow(NowPlayingRotation.ALBUM_COVER_FLOW, landscape, nowPlayingOpen = true, dismissed, gravity = true))
            }
        }
        // The gate is gravity alone: off, the choice applies as before.
        assertTrue(showsAlbumCoverFlow(NowPlayingRotation.ALBUM_COVER_FLOW, landscape = true, nowPlayingOpen = true, dismissed = false, gravity = false))
    }
}
