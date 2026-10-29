package io.sonora.core.data.conversion.work

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.R
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The converter's notifications on the "Conversion" channel: the ongoing progress notification of the foreground job
 * (an Android 16 Live Update with ProgressStyle, a plain determinate progress bar before), with Cancel, and a summary
 * when a batch ends.
 */
class ConversionNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** The foreground job's notification and service type for batch [batchId], run by work [workId]. */
    fun foregroundInfo(batchId: Long, workId: UUID, progress: BatchProgress?, replacing: Boolean): ForegroundInfo {
        ensureChannel()
        val notification = progressNotification(batchId, workId, progress, replacing)
        return ForegroundInfo(progressId(batchId), notification, foregroundServiceType())
    }

    /** "N converted, M failed" (or the replacement's counts) once [batch] has ended. */
    fun showSummary(batch: ConversionBatch, replacing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val counts = batch.counts
        val title = when {
            replacing -> R.string.conversion_replaced_title
            batch.status == BatchStatus.CANCELLED -> R.string.conversion_cancelled_title
            batch.status == BatchStatus.TIMED_OUT -> R.string.conversion_timed_out_title
            batch.status == BatchStatus.FAILED -> R.string.conversion_failed_title
            else -> R.string.conversion_finished_title
        }
        val text = if (replacing) {
            context.getString(R.string.conversion_replaced_text, counts.replacedOriginals, counts.failed)
        } else {
            batch.failureReason?.takeIf { batch.status == BatchStatus.FAILED }
                ?: context.getString(R.string.conversion_summary_text, counts.converted, counts.failed)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_conversion)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openConverter(batch.id))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(summaryId(batch.id), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    private fun progressNotification(batchId: Long, workId: UUID, progress: BatchProgress?, replacing: Boolean): Notification {
        val title = context.getString(if (replacing) R.string.conversion_replace_title else R.string.conversion_progress_title)
        val text = when {
            progress == null -> context.getString(R.string.conversion_progress_preparing)
            progress.currentFileName != null && progress.finished < progress.total ->
                context.getString(R.string.conversion_progress_text, progress.finished, progress.total, progress.currentFileName)
            else -> context.getString(R.string.conversion_progress_count, progress.finished, progress.total)
        }
        val value = ((progress?.fraction ?: 0f) * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX)
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_conversion)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openConverter(batchId))
            .addAction(0, context.getString(R.string.conversion_action_cancel), cancel)
            // Live Update on Android 16+: ongoing, titled, not colorized, not a group summary, channel above MIN.
            .setRequestPromotedOngoing(true)
        if (progress != null) {
            builder.setShortCriticalText(context.getString(R.string.conversion_progress_chip, progress.finished, progress.total))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(value)
                    .setProgressIndeterminate(progress == null)
                    .setStyledByProgress(true),
            )
        } else {
            builder.setProgress(PROGRESS_MAX, value, progress == null)
        }
        return builder.build()
    }

    /**
     * Opens the Converter tab showing batch [batchId] ([EXTRA_OPEN_CONVERSION_BATCH]). The request code is the batch's
     * own, so the intent never replaces the media notification's (request code 0, same activity, other extras).
     */
    private fun openConverter(batchId: Long): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_OPEN_CONVERSION_BATCH, batchId)
        return PendingIntent.getActivity(
            context,
            progressId(batchId),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.conversion_channel_name))
                .setDescription(context.getString(R.string.conversion_channel_description))
                .setShowBadge(false)
                .build(),
        )
    }

    /**
     * mediaProcessing (API 35+, 6 h per 24 h, ended through `Service.onTimeout`) is the type for transcoding; it does
     * not exist before Android 15, where dataSync is the closest type and has no time limit.
     */
    private fun foregroundServiceType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    companion object {
        /** Long extra on the launch intent of a conversion notification: show that batch in the Converter tab. */
        const val EXTRA_OPEN_CONVERSION_BATCH = "io.sonora.player.extra.OPEN_CONVERSION_BATCH"

        private const val CHANNEL_ID = "conversion"
        private const val PROGRESS_MAX = 1_000
        private const val PROGRESS_ID_BASE = 0x5C00_0000
        private const val SUMMARY_ID_BASE = 0x5D00_0000

        private fun progressId(batchId: Long) = PROGRESS_ID_BASE + (batchId and 0xFF_FFFF).toInt()

        private fun summaryId(batchId: Long) = SUMMARY_ID_BASE + (batchId and 0xFF_FFFF).toInt()
    }
}
