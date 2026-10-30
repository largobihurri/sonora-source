package io.sonora.player.ui

import android.view.WindowManager.LayoutParams
import io.sonora.core.domain.model.NowPlayingRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationAnimationTest {

    @Test
    fun `an open Now Playing that turns into its landscape layout turns its blocks itself`() {
        assertTrue(nowPlayingTurnsBlocks(nowPlayingOpen = true, NowPlayingRotation.NOW_PLAYING_LANDSCAPE, albumVisualCollectionDismissed = false))
    }

    @Test
    fun `a closed Now Playing leaves the turn to the system`() {
        assertFalse(nowPlayingTurnsBlocks(nowPlayingOpen = false, NowPlayingRotation.NOW_PLAYING_LANDSCAPE, albumVisualCollectionDismissed = false))
    }

    @Test
    fun `with the Album visual collection choice the system turns the screen into the visual collection`() {
        assertFalse(nowPlayingTurnsBlocks(nowPlayingOpen = true, NowPlayingRotation.ALBUM_VISUAL_COLLECTION, albumVisualCollectionDismissed = false))
    }

    @Test
    fun `once the visual collection asked for the landscape Now Playing, its blocks turn again`() {
        assertTrue(nowPlayingTurnsBlocks(nowPlayingOpen = true, NowPlayingRotation.ALBUM_VISUAL_COLLECTION, albumVisualCollectionDismissed = true))
    }

    @Test
    fun `with gravity mode on, the blocks never turn, whatever the rotation choice`() {
        for (rotation in NowPlayingRotation.entries) {
            for (dismissed in listOf(true, false)) {
                assertFalse(nowPlayingTurnsBlocks(nowPlayingOpen = true, rotation, dismissed, gravity = true))
            }
        }
    }

    @Test
    fun `the window rotates seamlessly only while the blocks turn themselves`() {
        assertEquals(LayoutParams.ROTATION_ANIMATION_SEAMLESS, windowRotationAnimation(blocksTurn = true))
        assertEquals(LayoutParams.ROTATION_ANIMATION_ROTATE, windowRotationAnimation(blocksTurn = false))
    }

    @Test
    fun `gravity mode's open Now Playing rotates the window seamlessly, its frame turning against it`() {
        assertTrue(nowPlayingRotatesSeamlessly(nowPlayingOpen = true, NowPlayingRotation.ALBUM_VISUAL_COLLECTION, albumVisualCollectionDismissed = false, gravity = true))
        assertTrue(nowPlayingRotatesSeamlessly(nowPlayingOpen = true, NowPlayingRotation.NOW_PLAYING_LANDSCAPE, albumVisualCollectionDismissed = false, gravity = false))
        assertFalse(nowPlayingRotatesSeamlessly(nowPlayingOpen = false, NowPlayingRotation.NOW_PLAYING_LANDSCAPE, albumVisualCollectionDismissed = false, gravity = true))
        assertFalse(nowPlayingRotatesSeamlessly(nowPlayingOpen = true, NowPlayingRotation.ALBUM_VISUAL_COLLECTION, albumVisualCollectionDismissed = false, gravity = false))
    }
}
