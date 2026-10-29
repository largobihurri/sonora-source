package io.sonora.core.playback

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * "Play X on Sonora" from the assistant on the phone: its `MEDIA_PLAY_FROM_SEARCH` intent (which also tells the
 * assistant Sonora takes voice requests) is played through the session as the same voice item Android Auto sends, so
 * both find music the same way ([SonoraLibraryCallback.onSetMediaItems]).
 */
object VoicePlayRequest {
    fun handles(intent: Intent?): Boolean = intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH

    /** Sends [intent]'s request to the playback session through a short-lived controller. */
    fun play(context: Context, intent: Intent) {
        val app = context.applicationContext
        val item = mediaItem(intent)
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            // The session runs the commands in order after resolving the request; the controller goes once they ran.
            Handler(Looper.getMainLooper()).postDelayed(controller::release, RELEASE_DELAY_MS)
        }, ContextCompat.getMainExecutor(app))
    }

    internal fun mediaItem(intent: Intent): MediaItem {
        val request = MediaItem.RequestMetadata.Builder()
            .setSearchQuery(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            .setExtras(intent.extras)
            .build()
        return MediaItem.Builder().setRequestMetadata(request).build()
    }

    private const val RELEASE_DELAY_MS = 10_000L
}
