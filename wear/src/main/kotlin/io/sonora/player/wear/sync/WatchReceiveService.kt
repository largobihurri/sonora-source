package io.sonora.player.wear.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.sonora.player.wear.R
import io.sonora.player.wear.SonoraWearApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Keeps the watch app in the foreground (a `dataSync` foreground service with an ongoing notification) while songs are being
 * received, so Android does not stop it mid-transfer. It owns no transfer: it stops itself once no session is running.
 */
class WatchReceiveService : Service() {
    private val scope = MainScope()
    private var keepAlive: KeepAliveController? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        latestStartId = startId
        val controller = keepAlive ?: KeepAliveController(scope, (application as SonoraWearApp).sync.activeSessions, ::letGo)
            .also { keepAlive = it }
        controller.onStart()
        return START_NOT_STICKY
    }

    /** No session is running: drop the notification and stop, unless a newer start arrived (its own watcher then decides). */
    private fun letGo() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    /** Android 15 limits `dataSync` services to six hours a day: let go; the phone resumes the transfer next time. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_sonora)
        .setContentTitle(getString(R.string.wear_sync_notification_title))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.wear_sync_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val CHANNEL_ID = "sonora.sync"
        const val NOTIFICATION_ID = 0x5E2
    }
}
