package io.sonora.core.cast.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The session's shuffle flag over a player that ignores shuffle, the way Media3's RemoteCastPlayer does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShuffleFlagPlayerTest {

    /** Like RemoteCastPlayer: no shuffle command, the flag always false; repeat works. */
    private class NoShufflePlayer(var shuffle: Boolean = false) : SimpleBasePlayer(Looper.getMainLooper()) {
        var repeat = Player.REPEAT_MODE_OFF

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SET_REPEAT_MODE).build())
            .setShuffleModeEnabled(shuffle)
            .setRepeatMode(repeat)
            .build()

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            repeat = repeatMode
            return Futures.immediateVoidFuture()
        }
    }

    private val wrapped = NoShufflePlayer()
    private val player = ShuffleFlagPlayer(wrapped)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `shuffle can always be set, whatever the wrapped player offers`() {
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
    }

    @Test
    fun `the flag holds what was set and tells listeners`() {
        val changes = mutableListOf<Boolean>()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                changes += shuffleModeEnabled
            }
        })

        player.shuffleModeEnabled = true
        idle()

        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(changes).containsExactly(true)
    }

    @Test
    fun `the flag survives events of the wrapped player`() {
        player.shuffleModeEnabled = true
        idle()

        player.repeatMode = Player.REPEAT_MODE_ALL
        idle()

        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(wrapped.repeat).isEqualTo(Player.REPEAT_MODE_ALL)
    }

    @Test
    fun `the flag starts as the wrapped player's`() {
        assertThat(ShuffleFlagPlayer(NoShufflePlayer(shuffle = true)).shuffleModeEnabled).isTrue()
    }
}
