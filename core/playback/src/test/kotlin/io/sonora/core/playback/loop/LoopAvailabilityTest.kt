package io.sonora.core.playback.loop

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.core.playback.SessionPlayer
import io.sonora.core.playback.audio.TestAudioFiles
import io.sonora.core.playback.output.BitPerfectOutput
import io.sonora.core.playback.output.MixerAttributesApplier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

/**
 * The service's wiring end to end, which the JVM can run: the real [LoopController] attached to the real ExoPlayer (as
 * `PlaybackService` does), DJ mode on, the bit-perfect gate wired and not armed, the sink chain of the app. What the DJ
 * screen reads ([LoopController.loop]) must say loops are available once a song plays, for every PCM encoding the decoders
 * hand over. (It cannot tell what a phone's decoders and outputs do: that is what the `SonoraDjLoop` log is for.)
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoopAvailabilityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<ExoPlayer>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val audioData = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
        Thread.sleep(maxOf(1L, data.size / 4 * 1_000L / 48_000))
    }

    private class DjMode(on: Boolean) : DjModeSettings {
        override val enabled = MutableStateFlow(on)
        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
        override fun setPerformance(performance: PerformanceMode) = Unit
    }

    private class Quantize : DjQuantizeSettings {
        override val quantize = MutableStateFlow(true)
        override fun setQuantize(on: Boolean) {
            quantize.value = on
        }
    }

    private class Analyses : DjTrackAnalyses {
        override fun analysisOf(uri: String): Flow<DjAnalysis?> = MutableStateFlow(null)
    }

    private class Outputs : AudioOutputMonitor {
        override val snapshots: Flow<AudioOutputSnapshot> = emptyFlow()
        override fun current() = AudioOutputSnapshot(emptyList())
    }

    private class Mixer : MixerAttributesApplier {
        override fun apply(deviceId: Int, option: MixerOption) = false
        override fun clear() = Unit
    }

    @After
    fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(audioData)
        scopes.forEach { it.cancel() }
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun wav(rate: Int, bits: Int, formatType: Int, frames: Int): ByteArray {
        val bytes = bits / 8
        val data = ByteBuffer.allocate(frames * 2 * bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) {
            when (bits) {
                16 -> data.putShort((it % 2000).toShort())
                24 -> data.put(0).putShort((it % 2000).toShort())
                else -> data.putFloat((it % 2000) / 4000f)
            }
        }
        return TestAudioFiles.wav(2, rate, bits, data.array(), formatType = formatType)
    }

    private fun availableWhilePlaying(rate: Int, bits: Int, formatType: Int): Boolean {
        val path = folder.newFile("song-$rate-$bits.wav").apply { writeBytes(wav(rate, bits, formatType, rate * 2)) }
        ShadowAudioTrack.addAudioDataListener(audioData)
        val djMode = DjMode(on = true)
        val controller = LoopController(Analyses(), Quantize(), djMode, object : io.sonora.core.dubmodel.DubModeSettings {
            override val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)
            override fun setEnabled(enabled: Boolean) = Unit
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val player = SessionPlayer.build(
            context,
            bitPerfect = BitPerfectOutput(Outputs(), Mixer()),
            loops = controller.hub,
        ) { io.sonora.core.domain.equalizer.EqualizerSettings() }.also { players += it }
        controller.attach(player, scope)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(path)))
        player.prepare()
        player.play()
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline && player.playerError == null) {
            looper.idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
            if (controller.loop.value.available) return true
        }
        assertThat(player.playerError).isNull()
        return false
    }

    @Test
    fun `loops are available while a 16-bit song plays with DJ mode on`() {
        assertThat(availableWhilePlaying(44_100, 16, formatType = 1)).isTrue()
    }

    @Test
    fun `loops are available while a 24-bit song plays with DJ mode on`() {
        assertThat(availableWhilePlaying(48_000, 24, formatType = 1)).isTrue()
    }

    @Test
    fun `loops are available while a float song plays with DJ mode on`() {
        assertThat(availableWhilePlaying(48_000, 32, formatType = 3)).isTrue()
    }
}
