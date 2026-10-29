package io.sonora.feature.settings.duplicates

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.library.duplicates.DuplicateNotifier
import io.sonora.feature.settings.R
import javax.inject.Inject

/**
 * The "duplicates piled up" notification on its own "Duplicates" channel (the system can mute it apart from the
 * others): quiet, dismissible, and tapping it opens the app with [EXTRA_OPEN_DUPLICATES]. Posted from the background
 * only when POST_NOTIFICATIONS is already granted; it is never asked for from here.
 */
class DuplicateNotifications @Inject constructor(@ApplicationContext private val context: Context) : DuplicateNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun notify(groups: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel()
        val text = context.resources.getQuantityString(R.plurals.duplicates_notification_text, groups, groups)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_duplicates)
            .setContentTitle(context.getString(R.string.duplicates_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(openDuplicates())
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false // Permission revoked between the check and the post.
        }
    }

    private fun openDuplicates(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_OPEN_DUPLICATES, true)
        return PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.duplicates_channel_name))
                .setDescription(context.getString(R.string.duplicates_channel_description))
                .setShowBadge(true)
                .build(),
        )
    }

    companion object {
        /** Boolean extra on the launch intent of the notification: open Duplicates. */
        const val EXTRA_OPEN_DUPLICATES = "io.sonora.player.extra.OPEN_DUPLICATES"

        private const val CHANNEL_ID = "duplicates"
        private const val NOTIFICATION_ID = 0x5D00_0001
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface DuplicateNotifierModule {
    @Binds
    fun bindDuplicateNotifier(impl: DuplicateNotifications): DuplicateNotifier
}
