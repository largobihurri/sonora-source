package io.sonora.core.playback.interruption

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.playback.interruption.CallInterruptionPolicy.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pauses the session player while another app holds the call audio profile and resumes it afterwards, as
 * [CallInterruptionPolicy] decides. It watches the audio mode (a listener from API 31; polled while playing before),
 * the communication device, the output devices, Bluetooth SCO and the device music is routed to (API 33+), and the
 * player's own play state. While a pause of its own stands it polls, to see the call profile go and time the resume.
 *
 * Everything runs on the main thread, the player's: [scope] must be a main-thread scope. Attach once the player is
 * built, detach before it is released.
 */
internal class CallInterruptionMonitor(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val policy: CallInterruptionPolicy = CallInterruptionPolicy(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    private val audioManager: AudioManager? = context.getSystemService()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var poll: Job? = null
    private var modeListeners: ModeListeners? = null

    /**
     * The play state Sonora itself just set, until the player reports it: that report is Sonora's, not the user's.
     * A flag around the call would not do: a change made from inside a player callback is reported after it returns.
     */
    private var ownPlayWhenReady: Boolean? = null

    /** Sonora paused for a call and means to resume: the service should stay. */
    val isHolding: Boolean get() = policy.isHolding

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = evaluate()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = evaluate()
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluate()
    }

    fun attach() {
        val audioManager = audioManager ?: return
        player.addListener(this)
        policy.onPlayer(isAudible(), byUser = false, now())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Without the listeners (a platform refusing them), the mode is polled while playing instead.
            val listeners = ModeListeners()
            try {
                listeners.register(audioManager)
                modeListeners = listeners
            } catch (e: RuntimeException) {
                Log.w(TAG, "No audio mode listener, polling", e)
                listeners.unregister(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        ContextCompat.registerReceiver(
            context,
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        evaluate()
    }

    fun detach() {
        val audioManager = audioManager ?: return
        poll?.cancel()
        poll = null
        player.removeListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modeListeners?.unregister(audioManager)
        modeListeners = null
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { context.unregisterReceiver(scoReceiver) }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Whatever comes next settles Sonora's own change (one refused, e.g. by audio focus, is never reported).
        val own = ownPlayWhenReady
        ownPlayWhenReady = null
        if (own == playWhenReady) return
        val byUser = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
        evaluate()
        apply(policy.onPlayer(isAudible(), byUser, now()))
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        evaluate()
        apply(policy.onPlayer(isAudible(), byUser = false, now()))
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) policy.cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Sonora's pause holds the player still, so a transition now is the user's (a skip, a new queue).
        policy.cancel()
    }

    /** Reads the call profile now and hands it to the policy. */
    private fun evaluate() {
        val audioManager = audioManager ?: return
        val active = CallAudioProfile.isActive(audioManager.mode, mediaRouteType(audioManager))
        apply(policy.onCallProfile(active, now()))
        ensurePolling()
    }

    private fun apply(action: Action) {
        when (action) {
            Action.NONE -> return
            Action.PAUSE -> {
                // Already paused (the report that says so is on its way): there is nothing of Sonora's to undo.
                if (!player.playWhenReady) return policy.cancel()
                Log.i(TAG, "Pausing: another app holds the call audio profile")
                setOwnPlayWhenReady(false)
            }
            Action.RESUME -> {
                Log.i(TAG, "Resuming after the call audio profile")
                setOwnPlayWhenReady(true)
            }
        }
        ensurePolling()
    }

    private fun setOwnPlayWhenReady(playWhenReady: Boolean) {
        if (player.playWhenReady == playWhenReady) return
        ownPlayWhenReady = playWhenReady
        player.playWhenReady = playWhenReady
    }

    /**
     * Polls while Sonora's pause stands (to see the call go, time the settle delay and the cap) and, without the mode
     * listener (before API 31), while the player is audible (to see a call come).
     */
    private fun ensurePolling() {
        if (poll?.isActive == true || !needsPolling()) return
        poll = scope.launch {
            while (isActive && needsPolling()) {
                delay(if (policy.isHolding) HOLD_POLL_MS else PLAY_POLL_MS)
                evaluate()
                if (policy.isHolding) {
                    val action = policy.tick(now(), otherMediaActive = audioManager?.isMusicActive == true)
                    if (action == Action.NONE && !policy.isHolding) Log.i(TAG, "Staying paused: too long, or other media plays")
                    apply(action)
                }
            }
            poll = null
        }
    }

    private fun needsPolling(): Boolean =
        policy.isHolding || (modeListeners == null && isAudible())

    private fun isAudible(): Boolean =
        player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /** The device music is routed to now, when the platform tells (API 33+). */
    private fun mediaRouteType(audioManager: AudioManager): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()?.type }.getOrNull()
        } else {
            null
        }

    /** The audio mode and communication device listeners (API 31+), each a reason to read the call profile again. */
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ModeListeners :
        AudioManager.OnModeChangedListener,
        AudioManager.OnCommunicationDeviceChangedListener {

        override fun onModeChanged(mode: Int) = evaluate()

        override fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) = evaluate()

        fun register(audioManager: AudioManager) {
            val executor = ContextCompat.getMainExecutor(context)
            audioManager.addOnModeChangedListener(executor, this)
            audioManager.addOnCommunicationDeviceChangedListener(executor, this)
        }

        fun unregister(audioManager: AudioManager) {
            runCatching { audioManager.removeOnModeChangedListener(this) }
            runCatching { audioManager.removeOnCommunicationDeviceChangedListener(this) }
        }
    }

    private companion object {
        const val TAG = "SonoraCallPause"
        const val HOLD_POLL_MS = 250L
        const val PLAY_POLL_MS = 1_000L

        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
