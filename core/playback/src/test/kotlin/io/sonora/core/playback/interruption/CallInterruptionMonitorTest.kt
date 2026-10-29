package io.sonora.core.playback.interruption

import android.media.AudioManager
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.SessionPlayer
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The monitor on the session's real player, with the audio mode changed under it as another app would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallInterruptionMonitorTest {

    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService<AudioManager>()!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var monitor: CallInterruptionMonitor

    @Before
    fun setUp() {
        player = SessionPlayer.build(context)
        monitor = CallInterruptionMonitor(context, player, scope).apply { attach() }
        player.play()
        idle()
    }

    @After
    fun tearDown() {
        monitor.detach()
        scope.cancel()
        player.release()
        idle()
    }

    private fun idle(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** Another app switches the audio mode; the platform's listeners are told through the communication device. */
    private fun mode(mode: Int) {
        audioManager.mode = mode
        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(null)
        idle()
    }

    @Test
    fun `pauses for a communication mode and resumes after the settle delay`() {
        mode(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(player.playWhenReady).isFalse()

        mode(AudioManager.MODE_NORMAL)
        idle(CallInterruptionPolicy.SETTLE_MS / 2)
        assertThat(player.playWhenReady).isFalse()
        idle(CallInterruptionPolicy.SETTLE_MS)
        assertThat(player.playWhenReady).isTrue()
    }

    @Test
    fun `a pause by the user during the call is kept`() {
        mode(AudioManager.MODE_IN_COMMUNICATION)
        player.play()
        player.pause()
        idle()

        mode(AudioManager.MODE_NORMAL)
        idle(5_000)
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `a player paused before the call stays paused`() {
        player.pause()
        idle()

        mode(AudioManager.MODE_IN_COMMUNICATION)
        mode(AudioManager.MODE_NORMAL)
        idle(5_000)
        assertThat(player.playWhenReady).isFalse()
    }
}
