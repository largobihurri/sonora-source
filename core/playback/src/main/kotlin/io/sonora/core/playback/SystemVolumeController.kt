package io.sonora.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.port.VolumeController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** [VolumeController] bound to [AudioManager.STREAM_MUSIC]. Listens only while [volume] is collected. */
@Singleton
class SystemVolumeController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VolumeController {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stream = AudioManager.STREAM_MUSIC
    private var emitCurrent: (() -> Unit)? = null

    private val minStep get() = audio.getStreamMinVolume(stream)
    private val maxStep get() = audio.getStreamMaxVolume(stream)

    private fun read(): Float = VolumeMapping.stepToFraction(audio.getStreamVolume(stream), minStep, maxStep)

    override val volume: StateFlow<Float> = callbackFlow {
        val emit = { trySend(read()); Unit }
        emitCurrent = emit
        // VOLUME_CHANGED_ACTION is a hidden *system* broadcast sent by the platform, so the receiver must be
        // RECEIVER_EXPORTED to receive it (NOT_EXPORTED only accepts our own app's broadcasts). It carries no data
        // we trust: on receipt we only re-read AudioManager.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = emit()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Belt and braces: some OEMs skip the broadcast but still persist the volume in Settings.System.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = emit()
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        emit()
        awaitClose {
            emitCurrent = null
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), read())

    override fun setVolume(fraction: Float) {
        val step = VolumeMapping.fractionToStep(fraction, minStep, maxStep)
        if (step != audio.getStreamVolume(stream)) audio.setStreamVolume(stream, step, 0)
        emitCurrent?.invoke()
    }

    override fun refresh() {
        emitCurrent?.invoke()
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
