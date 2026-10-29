package io.sonora.core.data.archive.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.R
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveProgress
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** What an archive job does, for its notification's wording. */
enum class ArchiveTask { COMPRESS, EXTRACT }

/**
 * The archive jobs' notifications on the "Archives" channel: the foreground job's ongoing progress (an Android 16
 * Live Update with ProgressStyle, a plain progress bar before) with Cancel, and the result once a job ends (not for a
 * job the user cancelled). Both open the app with [EXTRA_OPEN_ARCHIVE_JOB].
 */
class ArchiveNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * The foreground notification and service type of job [jobId], run by work [workId]. dataSync: packing or
     * unpacking files (mediaProcessing is for transcoding). The app's manifest declares WorkManager's
     * SystemForegroundService with `dataSync|mediaProcessing`, which this library's lint cannot see.
     */
    @SuppressLint("SpecifyForegroundServiceType")
    fun foregroundInfo(jobId: String, workId: UUID, task: ArchiveTask, progress: ArchiveProgress?): ForegroundInfo {
        ensureChannel()
        val notification = progressNotification(jobId, workId, task, progress)
        return ForegroundInfo(progressId(jobId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** The result of job [jobId] once it ended in [state]: the archive ready, the music added, or why it failed. */
    fun showResult(jobId: String, task: ArchiveTask, state: ArchiveJobState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val (title, text) = when (state) {
            is ArchiveJobState.Compressed -> context.getString(R.string.archive_compressed_title) to state.archive.fileName
            is ArchiveJobState.Extracted -> context.getString(R.string.archive_extracted_title) to
                context.resources.getQuantityString(R.plurals.archive_extracted_text, state.fileCount, state.fileCount)
            is ArchiveJobState.Failed -> context.getString(
                if (task == ArchiveTask.COMPRESS) R.string.archive_compress_failed_title else R.string.archive_extract_failed_title,
            ) to failureText(state.failure)
            else -> return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_conversion)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(jobId, resultId(jobId)))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(resultId(jobId), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** A sentence for [failure], as the result notification (and the app) show it. */
    fun failureText(failure: ArchiveFailure): String = when (failure) {
        ArchiveFailure.PasswordRequired -> context.getString(R.string.archive_error_password_required)
        ArchiveFailure.WrongPassword -> context.getString(R.string.archive_error_wrong_password)
        ArchiveFailure.PasswordNeededAgain -> context.getString(R.string.archive_error_password_again)
        ArchiveFailure.Damaged -> context.getString(R.string.archive_error_damaged)
        ArchiveFailure.NoMusic -> context.getString(R.string.archive_error_no_music)
        is ArchiveFailure.NotEnoughSpace -> context.getString(
            R.string.archive_error_no_space,
            Formatter.formatShortFileSize(context, failure.neededBytes),
            Formatter.formatShortFileSize(context, failure.availableBytes),
        )
        is ArchiveFailure.UnreadableSource ->
            if (failure.name.isBlank()) context.getString(R.string.archive_error_unreadable_files)
            else context.getString(R.string.archive_error_unreadable, failure.name)
        ArchiveFailure.DestinationUnwritable -> context.getString(R.string.archive_error_unwritable)
        ArchiveFailure.Unexpected -> context.getString(R.string.archive_error_unexpected)
    }

    private fun progressNotification(jobId: String, workId: UUID, task: ArchiveTask, progress: ArchiveProgress?): Notification {
        val title = context.getString(if (task == ArchiveTask.COMPRESS) R.string.archive_compress_title else R.string.archive_extract_title)
        val current = progress?.currentName
        val text = when {
            current == null -> context.getString(R.string.archive_progress_preparing)
            progress.entriesTotal > 0 ->
                context.getString(R.string.archive_progress_text, progress.entriesDone, progress.entriesTotal, current)
            else -> current
        }
        val percent = ((progress?.fraction ?: 0f) * 100).roundToInt()
        val value = ((progress?.fraction ?: 0f) * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX)
        val indeterminate = progress == null || progress.bytesTotal <= 0
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_conversion)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(jobId, progressId(jobId)))
            .addAction(0, context.getString(R.string.archive_action_cancel), cancel)
            // Live Update on Android 16+: ongoing, titled, not colorized, not a group summary, channel above MIN.
            .setRequestPromotedOngoing(true)
        if (!indeterminate) builder.setShortCriticalText(context.getString(R.string.archive_progress_chip, percent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(value)
                    .setProgressIndeterminate(indeterminate)
                    .setStyledByProgress(true),
            )
        } else {
            builder.setProgress(PROGRESS_MAX, value, indeterminate)
        }
        return builder.build()
    }

    /** Opens the app on job [jobId]; [requestCode] keeps each notification's intent apart. */
    private fun openApp(jobId: String, requestCode: Int): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_OPEN_ARCHIVE_JOB, jobId)
        return PendingIntent.getActivity(context, requestCode, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.archive_channel_name))
                .setDescription(context.getString(R.string.archive_channel_description))
                .setShowBadge(false)
                .build(),
        )
    }

    companion object {
        /** String extra on the launch intent of an archive notification: the job to show. */
        const val EXTRA_OPEN_ARCHIVE_JOB = "io.sonora.player.extra.OPEN_ARCHIVE_JOB"

        private const val CHANNEL_ID = "archives"
        private const val PROGRESS_MAX = 1_000
        private const val PROGRESS_ID_BASE = 0x5E00_0000
        private const val RESULT_ID_BASE = 0x5F00_0000

        private fun progressId(jobId: String) = PROGRESS_ID_BASE + (jobId.hashCode() and 0xFF_FFFF)

        private fun resultId(jobId: String) = RESULT_ID_BASE + (jobId.hashCode() and 0xFF_FFFF)
    }
}
