package io.sonora.core.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.sonora.core.cast.handover.CastHandover
import io.sonora.core.cast.player.CastMediaItemConverter
import io.sonora.core.cast.player.HandoverTransferCallback
import io.sonora.core.cast.player.ShuffleFlagPlayer
import io.sonora.core.cast.server.CastMediaServing
import io.sonora.core.cast.server.ContentFileOpener

/**
 * The session's player with casting: Media3's [CastPlayer] plays on the phone's player until a Cast session starts,
 * then on the receiver, and back when it ends ([HandoverTransferCallback], [CastHandover]); [ShuffleFlagPlayer] keeps
 * the shuffle flag across both. While casting, the receiver streams the queue from the phone's cast server
 * ([CastMediaServing]), which serves only the entries still in the receiver's queue and stops with the cast; losing
 * Wi-Fi ([WifiMonitor]) ends the cast, and playback comes back to the phone paused.
 *
 * [player] owns the local player: releasing it releases both. [release] stops the server and the Wi-Fi monitor.
 */
@OptIn(UnstableApi::class)
class CastRouting private constructor(context: Context, local: Player) {
    private val serving = CastMediaServing(ContentFileOpener(context.contentResolver))
    private val main = Handler(Looper.getMainLooper())
    private val wifi: WifiMonitor = WifiMonitor(context) { handover.onWifiLost() }
    private val handover: CastHandover = CastHandover(
        wifiAddress = { wifi.address() },
        server = serving,
        // Posted: the handover asks for it from inside the player's own transfer.
        endCastSession = { main.post { Cast.getSingletonInstance(context).endCurrentSession(true) } },
    )

    val player: Player = ShuffleFlagPlayer(
        CastPlayer.Builder(context)
            .setLocalPlayer(local)
            .setRemotePlayer(RemoteCastPlayer.Builder(context).setMediaItemConverter(CastMediaItemConverter { serving.urls }).build())
            .setTransferCallback(HandoverTransferCallback(handover))
            .build(),
    )

    init {
        wifi.start()
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (handover.isCasting) serving.urls?.retain(mediaIds(timeline))
            }
        })
    }

    @MainThread
    fun release() {
        wifi.stop()
        handover.release()
    }

    private fun mediaIds(timeline: Timeline): Set<String> {
        val window = Timeline.Window()
        return (0 until timeline.windowCount).mapTo(HashSet()) { timeline.getWindow(it, window).mediaItem.mediaId }
    }

    companion object {
        /** Casting around [local], or null on a device that cannot cast (no Google Play services). Main thread. */
        @MainThread
        fun create(context: Context, local: Player): CastRouting? {
            if (!CastSupport.isSupported(context)) return null
            CastSupport.initialize(context)
            return CastRouting(context, local)
        }
    }
}
