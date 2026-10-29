package io.sonora.core.cast

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Whether this device can cast: the Google Cast SDK needs Google Play services, so on devices without them every
 * cast affordance is hidden and the session keeps its plain local player.
 */
@OptIn(UnstableApi::class)
object CastSupport {
    @Volatile private var supported: Boolean? = null

    fun isSupported(context: Context): Boolean = supported ?: runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false).also { supported = it }

    /**
     * Starts loading the Cast context for the Default Media Receiver (no custom receiver), when supported. Called in
     * `Application.onCreate` (before any Cast button or player exists) and again by the playback service; repeated
     * calls are harmless.
     */
    @MainThread
    fun initialize(context: Context) {
        if (!isSupported(context)) return
        val params = CastParams.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
        Cast.getSingletonInstance(context).initialize(params)
    }
}
