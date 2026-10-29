package io.sonora.core.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.PcmEncoding
import io.sonora.core.playback.audio.TestAudioFiles
import io.sonora.core.playback.audio.TestAudioFiles.ramp
import java.time.Duration
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The session reports, per queue entry, what the decoder receives and what the AudioTrack was opened with. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DecodedFormatReporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private var player: ExoPlayer? = null

    @After
    fun tearDown() {
        player?.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a playing 24-bit AIFF is reported for its queue entry with big-endian input and float output`() {
        val path = folder.newFile("hi-res.aiff").apply { writeBytes(TestAudioFiles.aiff(2, 96_000, 24, ramp(9_600, 2, 3))) }
        var reported: List<DecodedAudioFormat> = emptyList()
        val player = SessionPlayer.build(context).also { player = it }
        player.addAnalyticsListener(DecodedFormatReporter { reported = it })
        player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(path)).setMediaId(QueueMediaId.format(trackId = 3, itemId = 42)).build())
        player.prepare()
        player.play()

        // ExoPlayer loads and decodes on its own threads, so the main looper is stepped until the output format is
        // reported. The deadline only guards against a hang: it is far above the normal time (well under a second) so
        // a slow or loaded CI machine never fails here, and a timeout fails as a timeout, not as a wrong format.
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + FORMAT_REPORT_TIMEOUT.toNanos()
        while (reported.none { it.outputEncoding != null } && player.playerError == null && System.nanoTime() < deadline) {
            looper.idleFor(LOOPER_STEP)
            Thread.sleep(LOOPER_STEP.toMillis())
        }

        assertThat(player.playerError).isNull()
        check(reported.any { it.outputEncoding != null }) {
            "No output format reported within $FORMAT_REPORT_TIMEOUT (state ${player.playbackState})"
        }
        assertThat(reported).containsExactly(
            DecodedAudioFormat(
                queueItemId = 42,
                inputMimeType = "audio/raw",
                inputEncoding = PcmEncoding.PCM_24_BIG_ENDIAN,
                inputSampleRateHz = 96_000,
                channelCount = 2,
                outputEncoding = PcmEncoding.FLOAT_32,
                outputSampleRateHz = 96_000,
            ),
        )
    }

    @Test
    fun `reported formats survive the trip through the session extras`() {
        val formats = listOf(
            DecodedAudioFormat(1, "audio/flac", null, 44_100, 2, PcmEncoding.PCM_16, 44_100),
            DecodedAudioFormat(2, null, PcmEncoding.FLOAT_32, null, null, null, null),
        )

        assertThat(DecodedFormatExtras.fromBundle(DecodedFormatExtras.toBundle(formats))).isEqualTo(formats)
        assertThat(DecodedFormatExtras.fromBundle(android.os.Bundle())).isEmpty()
    }

    @Test
    fun `Media3 PCM encodings map to the domain's, others to none`() {
        assertThat(DecodedFormatExtras.encodingOf(androidx.media3.common.C.ENCODING_PCM_16BIT)).isEqualTo(PcmEncoding.PCM_16)
        assertThat(DecodedFormatExtras.encodingOf(androidx.media3.common.C.ENCODING_PCM_32BIT_BIG_ENDIAN))
            .isEqualTo(PcmEncoding.PCM_32_BIG_ENDIAN)
        assertThat(DecodedFormatExtras.encodingOf(androidx.media3.common.C.ENCODING_PCM_FLOAT)).isEqualTo(PcmEncoding.FLOAT_32)
        assertThat(DecodedFormatExtras.encodingOf(androidx.media3.common.C.ENCODING_AC3)).isNull()
        assertThat(DecodedFormatExtras.encodingOf(androidx.media3.common.Format.NO_VALUE)).isNull()
    }

    private companion object {
        /** Generous upper bound for the AudioTrack to open under Robolectric; normally well under a second. */
        val FORMAT_REPORT_TIMEOUT: Duration = Duration.ofSeconds(90)

        /** Simulated main-looper time per step; the same wall time lets the playback thread progress. */
        val LOOPER_STEP: Duration = Duration.ofMillis(5)
    }
}
