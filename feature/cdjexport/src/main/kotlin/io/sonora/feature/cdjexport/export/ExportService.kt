package io.sonora.feature.cdjexport.export

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.designsystem.text.ResourceStrings
import io.sonora.feature.cdjexport.R
import io.sonora.feature.cdjexport.exportNotificationText
import io.sonora.feature.cdjexport.exportNotificationTitle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while an export runs (a `dataSync` foreground service with a progress notification),
 * so Android does not stop it when the user leaves the app. It only watches [ExportRunner.job]: the export itself runs
 * in the [ExportCoordinator], and the service stops itself as soon as the export is no longer running.
 *
 * A foreground service rather than WorkManager: the export is user-started, must start at once, cannot be retried
 * from scratch by a scheduler (it would re-copy half a stick) and holds live state (the open stick, progress) that
 * the screen shows; the project already runs its playback this way and has no WorkManager set up.
 */
@AndroidEntryPoint
class ExportService : Service() {

    @Inject
    lateinit var runner: ExportRunner

    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(runner.job.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watching == null) {
            watching = scope.launch {
                runner.job
                    .map { it to percentOf(it) }
                    .distinctUntilChanged { old, new -> old.second == new.second && sameTrack(old.first, new.first) }
                    .collect { (job, _) ->
                        if (job is ExportJob.Running) show(job) else stop()
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits `dataSync` services to six hours a day: stop the export cleanly when that runs out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.cancel()
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

    private fun show(job: ExportJob.Running) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(job))
    }

    /**
     * The app's resources, in the language chosen in Sonora: from Android 13 the system applies it to every context,
     * before that Sonora sets it on the application's resources only (and again after each configuration change), so
     * the service's own context is not relied on.
     */
    private val appResources get() = applicationContext.resources

    private fun notification(job: ExportJob): Notification {
        val strings = ResourceStrings(appResources)
        val progress = (job as? ExportJob.Running)?.progress
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(exportNotificationTitle(job, strings))
            .setContentText(exportNotificationText(job, strings))
            .setProgress(PERCENT, percentOf(job), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launch)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Creates the channel, or renames it in the current language (Android keeps its importance and the user's settings). */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, appResources.getString(R.string.cdj_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun percentOf(job: ExportJob): Int =
        ((job as? ExportJob.Running)?.progress?.overallFraction?.times(PERCENT))?.toInt() ?: 0

    private fun sameTrack(old: ExportJob, new: ExportJob): Boolean {
        if (old !is ExportJob.Running || new !is ExportJob.Running) return old::class == new::class
        return old.cancelling == new.cancelling && old.progress?.trackIndex == new.progress?.trackIndex &&
            old.progress?.phase == new.progress?.phase
    }

    private companion object {
        const val CHANNEL_ID = "sonora.export"
        const val NOTIFICATION_ID = 0x5E0
        const val PERCENT = 100
    }
}

/** Starts [ExportService] when an export starts. */
class ServiceExportForeground @Inject constructor(@ApplicationContext private val context: Context) : ExportForeground {
    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
    }
}

/**
 * [UriPermissions] through the content resolver's persistable grants, with the record of the export's own read grants
 * ([OwnedReadGrants]) in private preferences.
 */
@Singleton
class ResolverUriPermissions @Inject constructor(@ApplicationContext private val context: Context) : UriPermissions {
    private val resolver get() = context.contentResolver

    /** Built on first use (off the main thread: it reads the preferences). */
    private val readGrants by lazy {
        OwnedReadGrants(
            store = PreferencesGrantStore(context.getSharedPreferences(GRANTS_FILE, Context.MODE_PRIVATE)),
            system = object : SystemReadGrants {
                override fun held(): Set<String> =
                    resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }

                override fun take(uri: String): Boolean = take(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                override fun release(uri: String) {
                    try {
                        resolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: SecurityException) {
                        // Never kept, or already gone: nothing to give up.
                    }
                }
            },
        )
    }

    override fun takeRead(uri: String): Boolean = readGrants.take(uri)

    override fun takeReadWrite(treeUri: String): Boolean =
        take(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun heldReadWriteTrees(): Set<String> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission }
        .mapTo(HashSet()) { it.uri.toString() }

    override fun release(uri: String) = readGrants.release(uri)

    override fun releaseLeftovers() = readGrants.releaseLeftovers()

    private fun take(uri: String, flags: Int): Boolean = try {
        resolver.takePersistableUriPermission(uri.toUri(), flags)
        true
    } catch (e: SecurityException) {
        false
    }

    private class PreferencesGrantStore(private val preferences: SharedPreferences) : GrantStore {
        override fun load(): Set<String> = preferences.getStringSet(KEY_OWNED, null)?.toSet().orEmpty()

        override fun save(uris: Set<String>) {
            // A new set each time: SharedPreferences must not be handed back the set it returned.
            preferences.edit { putStringSet(KEY_OWNED, HashSet(uris)) }
        }
    }

    private companion object {
        const val GRANTS_FILE = "sonora_export_grants"
        const val KEY_OWNED = "owned_read_grants"
    }
}
