package io.sonora.core.watchphone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncState
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while a send to the watch runs (a `dataSync` foreground service with a progress
 * notification), so Android does not stop it when the user leaves the app. It only watches
 * [WatchSyncController.state]: the send itself runs in the controller, and the service stops itself as soon as no send is
 * running.
 */
@AndroidEntryPoint
class WatchSyncService : Service() {

    @Inject
    lateinit var controller: WatchSyncController

    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(controller.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watching == null) {
            watching = scope.launch {
                controller.state
                    .distinctUntilChanged { old, new -> sameNotification(old, new) }
                    .collect { state -> if (state is WatchSyncState.Running) show(state) else stop() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits `dataSync` services to six hours a day: stop the send cleanly (it resumes next time). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.cancel()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun show(state: WatchSyncState.Running) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state))
    }

    /** The app's resources, in the language chosen in Sonora (the service's own context is not relied on). */
    private val appResources get() = applicationContext.resources

    private fun notification(state: WatchSyncState): Notification {
        val running = state as? WatchSyncState.Running
        val progress = running?.progress
        val text = when {
            progress == null -> appResources.getString(R.string.watch_sync_connecting)
            progress.phase == io.sonora.core.watchsync.sender.SendProgress.Phase.ENCODING ->
                appResources.getString(R.string.watch_sync_encoding, progress.trackNumber, progress.trackCount, progress.title)
            else -> appResources.getString(R.string.watch_sync_sending, progress.trackNumber, progress.trackCount, progress.title)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(appResources.getString(R.string.watch_sync_title))
            .setContentText(text)
            .setProgress(PERCENT, percentOf(state), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launch)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, appResources.getString(R.string.watch_sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun percentOf(state: WatchSyncState): Int =
        ((state as? WatchSyncState.Running)?.progress?.overallFraction?.times(PERCENT))?.roundToInt() ?: 0

    /** Same text and bar: no need to post again. */
    private fun sameNotification(old: WatchSyncState, new: WatchSyncState): Boolean {
        if (old !is WatchSyncState.Running || new !is WatchSyncState.Running) return old::class == new::class
        val a = old.progress
        val b = new.progress
        return (a == null) == (b == null) && a?.trackNumber == b?.trackNumber && a?.phase == b?.phase &&
            percentOf(old) == percentOf(new)
    }

    private companion object {
        const val CHANNEL_ID = "sonora.watchsync"
        const val NOTIFICATION_ID = 0x5E1
        const val PERCENT = 100
    }
}

/** Starts [WatchSyncService] when a send starts. */
class ServiceWatchSyncForeground @Inject constructor(@ApplicationContext private val context: Context) : WatchSyncForeground {
    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, WatchSyncService::class.java))
    }
}
