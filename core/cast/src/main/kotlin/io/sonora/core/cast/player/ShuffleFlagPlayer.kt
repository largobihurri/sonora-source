package io.sonora.core.cast.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Holds the session's shuffle flag itself instead of passing it to [player].
 *
 * In Sonora the flag never reorders playback: it only mirrors the app's shuffle state, and the app shuffles the real
 * playlist (see core:playback's SessionPlayer and QueueEditor). Media3's RemoteCastPlayer ignores the flag and always
 * reports false, so while casting the queue owner would read that as "shuffle turned off by another controller" and
 * unshuffle the user's queue. Over this wrapper the flag keeps its value on the phone and on a receiver alike, and
 * shuffle stays available to every controller.
 */
@OptIn(UnstableApi::class)
class ShuffleFlagPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    private var shuffle = player.shuffleModeEnabled

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon()
            .setShuffleModeEnabled(shuffle)
            .setAvailableCommands(state.availableCommands.buildUpon().add(COMMAND_SET_SHUFFLE_MODE).build())
            .build()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffle = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }
}
