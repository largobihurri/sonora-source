package io.sonora.player.wear.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import io.sonora.core.watchsync.WatchSyncPaths
import io.sonora.player.wear.SonoraWearApp

/**
 * Woken by the Data Layer when the phone opens the sync channel, even if the app is not running. It hands the channel to the
 * application's [WatchSyncReceiver] and asks for the foreground service that keeps the process alive during the transfer.
 */
class WatchSyncListenerService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(WatchSyncPaths.SYNC_CHANNEL_PREFIX)) return
        (application as SonoraWearApp).sync.accept(channel)
        startKeepAlive(this)
    }

    internal companion object {
        /**
         * Starts [WatchReceiveService]. Android may refuse a foreground service started from the background; the transfer
         * does not depend on it (it runs on the application scope), only its chance of surviving a long run does.
         */
        fun startKeepAlive(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchReceiveService::class.java))
            } catch (e: RuntimeException) {
                Log.w("SonoraWearSync", "Cannot start the foreground service: ${e.message}")
            }
        }
    }
}
