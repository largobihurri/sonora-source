package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import androidx.media3.common.Player
import io.sonora.core.playback.PlaybackLogic.PreviousAction
import org.junit.Test

class PlaybackLogicTest {

    @Test
    fun `previous restarts after three seconds`() {
        assertThat(PlaybackLogic.previousAction(3_001, hasPrevious = true)).isEqualTo(PreviousAction.RESTART)
    }

    @Test
    fun `previous goes back within first three seconds`() {
        assertThat(PlaybackLogic.previousAction(3_000, hasPrevious = true)).isEqualTo(PreviousAction.PREVIOUS_ITEM)
        assertThat(PlaybackLogic.previousAction(0, hasPrevious = true)).isEqualTo(PreviousAction.PREVIOUS_ITEM)
    }

    @Test
    fun `previous restarts when there is no previous item`() {
        assertThat(PlaybackLogic.previousAction(500, hasPrevious = false)).isEqualTo(PreviousAction.RESTART)
    }

    @Test
    fun `start index is clamped`() {
        assertThat(PlaybackLogic.safeStartIndex(5, 3)).isEqualTo(2)
        assertThat(PlaybackLogic.safeStartIndex(-1, 3)).isEqualTo(0)
        assertThat(PlaybackLogic.safeStartIndex(4, 0)).isEqualTo(0)
    }

    private fun showsAsPlaying(
        playWhenReady: Boolean,
        state: Int,
        suppression: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    ) = PlaybackLogic.showsAsPlaying(playWhenReady, state, suppression)

    @Test
    fun `a buffering track that was asked to play shows as playing`() {
        assertThat(showsAsPlaying(true, Player.STATE_BUFFERING)).isTrue()
    }

    @Test
    fun `a ready track that was asked to play shows as playing`() {
        assertThat(showsAsPlaying(true, Player.STATE_READY)).isTrue()
    }

    @Test
    fun `a track that was not asked to play shows as paused in every state`() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            assertThat(showsAsPlaying(false, state)).isFalse()
        }
    }

    @Test
    fun `an idle or ended player shows as paused even when asked to play`() {
        assertThat(showsAsPlaying(true, Player.STATE_IDLE)).isFalse()
        assertThat(showsAsPlaying(true, Player.STATE_ENDED)).isFalse()
    }

    @Test
    fun `a suppressed player shows as paused even when asked to play`() {
        val suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        assertThat(showsAsPlaying(true, Player.STATE_READY, suppression)).isFalse()
        assertThat(showsAsPlaying(true, Player.STATE_BUFFERING, suppression)).isFalse()
    }
}
