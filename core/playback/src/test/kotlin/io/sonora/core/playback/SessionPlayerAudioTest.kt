package io.sonora.core.playback

import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.LevelBand
import io.sonora.core.domain.playback.PlaybackBandLevels
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.playback.audio.AiffExtractor
import io.sonora.core.playback.audio.SonoraExtractorsFactory
import io.sonora.core.playback.audio.TestAudioFiles
import io.sonora.core.playback.audio.TestAudioFiles.ramp
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.playback.levels.LiveBandLevels
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.abs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.annotation.Config

/**
 * The session's real ExoPlayer playing generated files from disk: AIFF goes through [AiffExtractor], and
 * high-resolution PCM reaches the AudioTrack as float instead of being cut to 16-bit.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionPlayerAudioTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val players = mutableListOf<ExoPlayer>()

    private class Played(
        val inputFormat: Format,
        val durationMs: Long,
        val track: AudioSink.AudioTrackConfig,
        val written: ByteArray,
        /** The AudioTrack's playback params; null when nothing set them (it plays at normal speed and pitch). */
        val output: PlaybackParams?,
        /** How many AudioTracks the player opened so far: a second one means the sink was reconfigured. */
        val trackInits: () -> Int,
    )

    /** Everything the player wrote to its AudioTracks. */
    private val written = ByteArrayOutputStream()

    /** The AudioTrack last written to: its playback params are the speed and pitch actually heard. */
    @Volatile
    private var lastTrack: AudioTrack? = null
    private val audioData = ShadowAudioTrack.OnAudioDataWrittenListener { audioTrack, data, _ ->
        lastTrack = audioTrack
        synchronized(written) { written.write(data) }
    }

    @After
    fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(audioData)
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Plays [file] until the audio sink opened its AudioTrack and was given at least [minimumBytes]. */
    private fun play(
        name: String,
        file: ByteArray,
        equalizer: EqualizerSettings = EqualizerSettings(),
        minimumBytes: Int = 0,
        levels: LiveBandLevels? = null,
        parameters: PlaybackParameters = PlaybackParameters.DEFAULT,
    ): Played {
        val path = folder.newFile(name).apply { writeBytes(file) }
        ShadowAudioTrack.addAudioDataListener(audioData)
        val player = SessionPlayer.build(context, levels) { equalizer }.also { players += it }
        var input: Format? = null
        var track: AudioSink.AudioTrackConfig? = null
        var trackInits = 0
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                input = format
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                track = config
                trackInits++
            }
        })
        player.playbackParameters = parameters
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(path)))
        player.prepare()
        player.play()
        // ExoPlayer loads and renders on its own threads, so the main looper is stepped until the AudioTrack opens.
        // The deadline only guards against a hang: it is far above the normal time (well under a second) so a slow
        // or loaded CI machine never fails here, and a timeout is reported as such rather than as a null format.
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + AUDIO_TRACK_TIMEOUT.toNanos()
        fun enoughData() = synchronized(written) { written.size() } >= minimumBytes
        while ((track == null || !enoughData()) && player.playerError == null && System.nanoTime() < deadline) {
            looper.idleFor(LOOPER_STEP)
            Thread.sleep(LOOPER_STEP.toMillis())
        }
        assertThat(player.playerError).isNull()
        check(track != null) { "AudioTrack not initialized within $AUDIO_TRACK_TIMEOUT (state ${player.playbackState})" }
        check(enoughData()) { "Only ${written.size()} bytes written within $AUDIO_TRACK_TIMEOUT" }
        assertThat(player.playbackState).isAnyOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED)
        val bytes = synchronized(written) { written.toByteArray() }
        return Played(checkNotNull(input), player.duration, checkNotNull(track), bytes, lastTrack?.playbackParams) { trackInits }
    }

    private companion object {
        /** Generous upper bound for one file to reach an initialized AudioTrack under Robolectric. */
        val AUDIO_TRACK_TIMEOUT: Duration = Duration.ofSeconds(90)

        /** Simulated main-looper time per step; the same wall time lets the playback thread progress. */
        val LOOPER_STEP: Duration = Duration.ofMillis(5)
    }

    @Test
    fun `the session player puts the AIFF extractor first`() {
        val factory = SonoraExtractorsFactory()

        assertThat(factory.createExtractors().first()).isInstanceOf(AiffExtractor::class.java)
        assertThat(factory.createExtractors(Uri.parse("content://doc/song.flac"), emptyMap()).first())
            .isInstanceOf(AiffExtractor::class.java)
        assertThat(factory.createExtractors().size).isGreaterThan(10)
    }

    @Test
    fun `24-bit 96 kHz AIFF plays through a float AudioTrack at its own rate`() {
        val file = TestAudioFiles.aiff(2, 96_000, 24, ramp(96_000, 2, 3))

        val played = play("hi-res.aiff", file)

        assertThat(played.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_24BIT_BIG_ENDIAN)
        assertThat(played.inputFormat.sampleRate).isEqualTo(96_000)
        assertThat(played.durationMs).isEqualTo(1_000)
        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.track.sampleRate).isEqualTo(96_000)
    }

    @Test
    fun `32-bit float AIFF-C at 192 kHz plays through a float AudioTrack`() {
        val file = TestAudioFiles.aiff(2, 192_000, 32, ramp(19_200, 2, 4), compression = "fl32")

        val played = play("float.aif", file)

        assertThat(played.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_FLOAT_BIG_ENDIAN)
        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.track.sampleRate).isEqualTo(192_000)
    }

    @Test
    fun `16-bit AIFF stays on a 16-bit AudioTrack`() {
        val played = play("cd.aiff", TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2)))

        assertThat(played.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(played.track.sampleRate).isEqualTo(44_100)
    }

    @Test
    fun `24-bit and 32-bit float WAV play through a float AudioTrack`() {
        val pcm24 = play("hi-res.wav", TestAudioFiles.wav(2, 96_000, 24, ramp(9_600, 2, 3), formatType = 1))
        val float = play("float.wav", TestAudioFiles.wav(2, 48_000, 32, ramp(4_800, 2, 4), formatType = 0xFFFE, subFormat = 3))

        assertThat(pcm24.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_24BIT)
        assertThat(pcm24.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(float.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(float.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
    }

    @Test
    fun `with the equalizer on, a 16-bit file plays through a float AudioTrack`() {
        val on = EqualizerSettings(enabled = true)

        val played = play("cd-eq.aiff", TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2)), on)

        assertThat(played.inputFormat.pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.track.sampleRate).isEqualTo(44_100)
    }

    @Test
    fun `the equalizer shapes 24-bit audio on its way to the AudioTrack and passes it untouched when off`() {
        val file = TestAudioFiles.wav(1, 48_000, 24, sine24(1_000.0, 48_000, 48_000, 0.5), formatType = 1)
        val cut = EqualizerSettings(enabled = true, mode = EqualizerMode.ELEVEN_BANDS).withGain(EqualizerMode.ELEVEN_BANDS, 6, -12f)

        val shaped = play("eq-on.wav", file, cut, minimumBytes = 12_000 * 4)
        // The first player would keep writing into the capture otherwise.
        players.forEach { it.release() }
        synchronized(written) { written.reset() }
        val untouched = play("eq-off.wav", file, minimumBytes = 12_000 * 4)

        assertThat(shaped.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(20 * log10(amplitude(shaped.written) / 0.5)).isWithin(0.2).of(-12.0)
        assertThat(20 * log10(amplitude(untouched.written) / 0.5)).isWithin(0.05).of(0.0)
    }

    @Test
    fun `while watched, the session player measures the bands of what it plays`() {
        val levels = LiveBandLevels()
        levels.watch()
        val file = TestAudioFiles.wav(2, 48_000, 16, sine16Stereo(1_000.0, 48_000, 48_000, 0.5), formatType = 1)

        play("levels.wav", file, levels = levels, minimumBytes = 12_000 * 2)

        val newest = FloatArray(PlaybackBandLevels.BAND_COUNT)
        assertThat(levels.newestFrame(newest)).isNotNull()
        assertThat(newest.indices.maxBy { newest[it] }).isEqualTo(LevelBand.MIDS.ordinal)
        assertThat(newest[LevelBand.MIDS.ordinal].toDouble()).isGreaterThan(-20.0)
    }

    @Test
    fun `high-resolution audio reaches a float AudioTrack at the speed and pitch asked for`() {
        val tempo = PlaybackParameters(1.5f, PlaybackTempo.pitchFactorOf(2))
        val file = TestAudioFiles.wav(2, 96_000, 24, ramp(96_000, 2, 3), formatType = 1)

        val played = play("tempo-hi-res.wav", file, minimumBytes = 96_000, parameters = tempo)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.output?.speed).isEqualTo(tempo.speed)
        assertThat(played.output?.pitch).isEqualTo(tempo.pitch)
    }

    @Test
    fun `with the equalizer on, audio reaches the AudioTrack at the speed and pitch asked for`() {
        val tempo = PlaybackParameters(0.75f, PlaybackTempo.pitchFactorOf(-5))
        val file = TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2))

        val played = play("tempo-eq.aiff", file, EqualizerSettings(enabled = true), minimumBytes = 44_100, parameters = tempo)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.output?.speed).isEqualTo(tempo.speed)
        assertThat(played.output?.pitch).isEqualTo(tempo.pitch)
    }

    @Test
    fun `16-bit audio reaches a 16-bit AudioTrack at the speed and pitch asked for`() {
        val tempo = PlaybackParameters(2f, PlaybackTempo.pitchFactorOf(12))

        val played = play("tempo-cd.aiff", TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2)), minimumBytes = 44_100, parameters = tempo)

        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(played.output?.speed).isEqualTo(tempo.speed)
        assertThat(played.output?.pitch).isEqualTo(tempo.pitch)
    }

    @Test
    fun `a speed in basis points reaches the AudioTrack exactly`() {
        val tempo = PlaybackTempo(speedBasisPoints = 10_123).toPlaybackParameters()
        val file = TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2))

        val played = play("tempo-fine.aiff", file, minimumBytes = 44_100, parameters = tempo)

        assertThat(played.output?.speed).isEqualTo(1.0123f)
        assertThat(played.output?.pitch).isEqualTo(1f)
    }

    @Test
    fun `with Master Tempo off the speed and the pitch are the same on the AudioTrack`() {
        val tempo = PlaybackTempo(speedBasisPoints = 10_612, masterTempo = false).toPlaybackParameters()
        val file = TestAudioFiles.aiff(2, 44_100, 16, ramp(44_100, 2, 2))

        val played = play("tempo-vinyl.aiff", file, minimumBytes = 44_100, parameters = tempo)

        assertThat(played.output?.speed).isEqualTo(1.0612f)
        assertThat(played.output?.pitch).isEqualTo(1.0612f)
    }

    @Test
    fun `high-resolution audio stays float and on the same AudioTrack after a speed change returns to neutral`() {
        // Media3's Sonic speed processor only handles 16-bit PCM. The session player never lets it near the audio:
        // with output playback parameters on, speed and pitch go to the AudioTrack and the sink's own processors keep
        // their defaults, so a speed change can neither drop float output to 16-bit nor leave it there afterwards.
        val tempo = PlaybackParameters(1.5f, PlaybackTempo.pitchFactorOf(2))
        val file = TestAudioFiles.wav(2, 96_000, 24, ramp(96_000, 2, 3), formatType = 1)

        val played = play("tempo-round-trip.wav", file, minimumBytes = 96_000, parameters = tempo)
        assertThat(played.output?.speed).isEqualTo(tempo.speed)
        val player = players.last()
        player.playbackParameters = PlaybackParameters.DEFAULT
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + AUDIO_TRACK_TIMEOUT.toNanos()
        while (lastTrack?.playbackParams?.speed != 1f && System.nanoTime() < deadline) {
            looper.idleFor(LOOPER_STEP)
            Thread.sleep(LOOPER_STEP.toMillis())
        }

        assertThat(lastTrack?.playbackParams?.speed).isEqualTo(1f)
        assertThat(lastTrack?.playbackParams?.pitch).isEqualTo(1f)
        assertThat(played.trackInits()).isEqualTo(1)
        assertThat(played.track.encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(played.track.sampleRate).isEqualTo(96_000)
    }

    @Test
    fun `a 24-bit file reaches the AudioTrack as the exact float of its samples at neutral speed`() {
        val frames = 4_800
        val source = ramp(frames, 1, 3)
        val file = TestAudioFiles.wav(1, 48_000, 24, source, formatType = 1)

        val played = play("exact.wav", file, minimumBytes = frames * 4)

        // Samples 1_000 to 1_099 of the file, widened to float by hand (24-bit LE, scaled by 2^-23).
        val expected = ByteBuffer.allocate(100 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 1_000 until 1_100) {
            val v = (source[3 * i].toInt() and 0xFF) or ((source[3 * i + 1].toInt() and 0xFF) shl 8) or (source[3 * i + 2].toInt() shl 16)
            expected.putFloat(v / 8_388_608f)
        }
        assertThat(indexOf(played.written, expected.array())).isAtLeast(0)
    }

    private fun indexOf(data: ByteArray, part: ByteArray): Int {
        outer@ for (start in 0..data.size - part.size) {
            for (i in part.indices) if (data[start + i] != part[i]) continue@outer
            return start
        }
        return -1
    }

    /** 16-bit little-endian stereo sine, the same on both channels. */
    private fun sine16Stereo(frequency: Double, rate: Int, frames: Int, amplitude: Double): ByteArray {
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = Math.round(amplitude * 32_767 * sin(2 * PI * frequency * i / rate)).toInt().toShort()
            out.putShort(v).putShort(v)
        }
        return out.array()
    }

    /** 24-bit little-endian mono sine. */
    private fun sine24(frequency: Double, rate: Int, frames: Int, amplitude: Double): ByteArray {
        val out = ByteArray(frames * 3)
        for (i in 0 until frames) {
            val v = Math.round(amplitude * 8_388_608 * sin(2 * PI * frequency * i / rate)).toInt()
            out[3 * i] = v.toByte()
            out[3 * i + 1] = (v shr 8).toByte()
            out[3 * i + 2] = (v shr 16).toByte()
        }
        return out
    }

    /**
     * A sine's amplitude: the peak of float samples 0.1 s to 0.25 s in, after the equalizer's fade-in. A peak, not an
     * RMS: under Robolectric the captured stream also holds short fade-ins of its own (seen at 0 s and 0.1 s, with
     * the equalizer off too), which would pull an RMS down.
     */
    private fun amplitude(floatData: ByteArray): Double {
        val samples = ByteBuffer.wrap(floatData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return (4_800 until 12_000).maxOf { abs(samples[it].toDouble()) }
    }
}
