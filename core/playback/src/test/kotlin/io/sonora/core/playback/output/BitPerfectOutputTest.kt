package io.sonora.core.playback.output

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.core.playback.SessionPlayer
import io.sonora.core.playback.audio.TestAudioFiles
import io.sonora.core.playback.audio.TestAudioFiles.ramp
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * The session's real ExoPlayer with bit-perfect output armed over a pretend USB DAC: the AudioTrack opens in the
 * DAC's own format, Android is asked to mix in it, and the samples written are the file's, widened where the DAC's
 * format is deeper than what the sink makes.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BitPerfectOutputTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<ExoPlayer>()
    private val written = ByteArrayOutputStream()
    private val audioData = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> synchronized(written) { written.write(data) } }

    private class FakeOutputs(device: AudioOutputDevice) : AudioOutputMonitor {
        private val snapshot = AudioOutputSnapshot(listOf(device), mixerApi = true)
        override val snapshots: Flow<AudioOutputSnapshot> = flowOf(snapshot)
        override fun current() = snapshot
    }

    private class FakeApplier : MixerAttributesApplier {
        val applied = mutableListOf<Pair<Int, MixerOption>>()
        var cleared = 0
        override fun apply(deviceId: Int, option: MixerOption): Boolean {
            applied += deviceId to option
            return true
        }

        override fun clear() {
            cleared++
        }
    }

    private fun dac(vararg options: MixerOption) = AudioOutputDevice(
        9, OutputDeviceKind.USB, "Test DAC", emptyList(), emptyList(), emptyList(), options.toList(), null,
    )

    private fun option(rate: Int, encoding: OutputEncoding) = MixerOption(rate, 2, encoding, bitPerfect = true)

    private class Played(val track: AudioSink.AudioTrackConfig, val bytes: ByteArray)

    @After
    fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(audioData)
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun play(name: String, file: ByteArray, bitPerfect: BitPerfectOutput, minimumBytes: Int, equalizer: EqualizerSettings = EqualizerSettings()): Played {
        val path = folder.newFile(name).apply { writeBytes(file) }
        ShadowAudioTrack.addAudioDataListener(audioData)
        val replayGain = ReplayGainSettings(ReplayGainMode.TRACK)
        val player = SessionPlayer.build(context, replayGain = { replayGain }, bitPerfect = bitPerfect) { equalizer }.also { players += it }
        var track: AudioSink.AudioTrackConfig? = null
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                track = config
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(path)))
        player.prepare()
        player.play()
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while ((track == null || synchronized(written) { written.size() } < minimumBytes) && player.playerError == null && System.nanoTime() < deadline) {
            looper.idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
        }
        assertThat(player.playerError).isNull()
        check(track != null) { "AudioTrack not initialized" }
        return Played(checkNotNull(track), synchronized(written) { written.toByteArray() })
    }

    private fun armed(device: AudioOutputDevice, applier: FakeApplier, log: MutableList<String> = mutableListOf()) =
        BitPerfectOutput(FakeOutputs(device), applier) { log += it }.also { it.armed = true }

    private fun indexOf(data: ByteArray, part: ByteArray): Int {
        outer@ for (start in 0..data.size - part.size) {
            for (i in part.indices) if (data[start + i] != part[i]) continue@outer
            return start
        }
        return -1
    }

    @Test
    fun `a 24-bit file plays in the DAC's 24-bit format with every sample exact`() {
        val applier = FakeApplier()
        val source = ramp(48_000, 2, 3)
        val file = TestAudioFiles.wav(2, 48_000, 24, source, formatType = 1)

        val played = play("a.wav", file, armed(dac(option(48_000, OutputEncoding.PCM_24_PACKED), option(48_000, OutputEncoding.PCM_32)), applier), minimumBytes = 48_000 * 6)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_24BIT)
        assertThat(played.track.sampleRate).isEqualTo(48_000)
        assertThat(applier.applied).containsExactly(9 to option(48_000, OutputEncoding.PCM_24_PACKED))
        // 100 frames from the middle of the file, byte for byte.
        assertThat(indexOf(played.bytes, source.copyOfRange(6 * 4_000, 6 * 4_100))).isAtLeast(0)
    }

    @Test
    fun `a 16-bit file is widened to the DAC's 24-bit format without changing a sample`() {
        val applier = FakeApplier()
        val source = ramp(44_100, 2, 2)
        val file = TestAudioFiles.wav(2, 44_100, 16, source, formatType = 1)

        val played = play("b.wav", file, armed(dac(option(44_100, OutputEncoding.PCM_24_PACKED)), applier), minimumBytes = 44_100 * 6, equalizer = EqualizerSettings(enabled = true))

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_24BIT)
        val widened = ByteArrayOutputStream()
        for (sample in 4_000 until 4_200) {
            widened.write(0)
            widened.write(source[2 * sample].toInt())
            widened.write(source[2 * sample + 1].toInt())
        }
        assertThat(indexOf(played.bytes, widened.toByteArray())).isAtLeast(0)
    }

    @Test
    fun `a 16-bit file stays on a 16-bit track when the DAC offers 16-bit`() {
        val applier = FakeApplier()
        val file = TestAudioFiles.wav(2, 44_100, 16, ramp(44_100, 2, 2), formatType = 1)

        val played = play("c.wav", file, armed(dac(option(44_100, OutputEncoding.PCM_16)), applier), minimumBytes = 44_100 * 4)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(applier.applied).hasSize(1)
    }

    @Test
    fun `a file whose rate the DAC does not offer plays the normal way and gives Android its mixing back`() {
        val applier = FakeApplier()
        val log = mutableListOf<String>()
        val file = TestAudioFiles.wav(2, 44_100, 16, ramp(44_100, 2, 2), formatType = 1)

        val played = play("d.wav", file, armed(dac(option(48_000, OutputEncoding.PCM_16)), applier, log), minimumBytes = 44_100 * 4)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(played.track.sampleRate).isEqualTo(44_100)
        assertThat(applier.applied).isEmpty()
        assertThat(applier.cleared).isAtLeast(1)
        assertThat(log.single { it.startsWith("Bit-perfect unavailable") }).contains("SAMPLE_RATE_NOT_OFFERED")
    }

    @Test
    fun `not armed, nothing about the output changes`() {
        val applier = FakeApplier()
        val gate = BitPerfectOutput(FakeOutputs(dac(option(96_000, OutputEncoding.PCM_24_PACKED))), applier) {}
        val file = TestAudioFiles.wav(2, 96_000, 24, ramp(96_000, 2, 3), formatType = 1)

        val played = play("e.wav", file, gate, minimumBytes = 96_000 * 6)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(applier.applied).isEmpty()
    }
}
