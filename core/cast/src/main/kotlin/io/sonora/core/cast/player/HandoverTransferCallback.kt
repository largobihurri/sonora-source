package io.sonora.core.cast.player

import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.sonora.core.cast.handover.CastHandover
import io.sonora.core.cast.handover.ToPhone

/**
 * Moves playback between the phone's player and the receiver as [handover] decides. Media3's default transfer copies
 * the queue, the current entry, the position, repeat and play/pause; the shuffle flag lives above both players
 * ([ShuffleFlagPlayer]). A queue coming back from a receiver arrives paused.
 */
@OptIn(UnstableApi::class)
internal class HandoverTransferCallback(private val handover: CastHandover) : CastPlayer.TransferCallback {
    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (targetPlayer is RemoteCastPlayer) {
            if (handover.toCast()) CastPlayer.TransferCallback.DEFAULT.transferState(sourcePlayer, targetPlayer)
            return
        }
        when (handover.toPhone()) {
            ToPhone.TAKE_CAST_STATE_PAUSED -> {
                CastPlayer.TransferCallback.DEFAULT.transferState(sourcePlayer, targetPlayer)
                targetPlayer.playWhenReady = false
            }
            ToPhone.KEEP_PHONE_STATE -> Unit
        }
    }
}
