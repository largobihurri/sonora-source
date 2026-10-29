package io.sonora.core.analysis

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.sonora.core.decoder.AndroidPcmDecoder
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.MusicalKey
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** End to end on the device: generated WAV -> AndroidPcmDecoder -> AndroidTrackAnalyzer -> native qm-dsp analyser. */
@RunWith(AndroidJUnit4::class)
class NativeTrackAnalyzerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val analyzer = AndroidTrackAnalyzer(
        AndroidPcmDecoder(context, Dispatchers.IO),
        AnalysisEngineFactory(NativeAnalyzer::create),
        Dispatchers.Default,
    )

    @Test
    fun analysesA128BpmClickTrackInAMinor() {
        val file = clickTrackWav("click_128_a_minor_20s.wav", seconds = 20.0)
        val progress = mutableListOf<Float>()

        val analysis = runBlocking { analyzer.analyze(Uri.fromFile(file).toString()) { progress += it } }

        Log.i(PERF_TAG, "20 s click track: bpm=${analysis.bpm} firstBeatMs=${analysis.beatGrid.firstBeatMs} (expected 300) " +
            "inBar=${analysis.beatGrid.firstBeatInBar} key=${analysis.key?.alphanumeric}")
        assertThat(analysis.bpm).isWithin(0.01).of(128.0)
        assertThat(analysis.key).isEqualTo(MusicalKey.A_MINOR)
        assertThat(analysis.key!!.alphanumeric).isEqualTo("8A")
        assertThat(analysis.beatGrid.firstBeatMs).isWithin(12.0).of(300.0)
        assertThat(analysis.beatGrid.firstBeatInBar).isEqualTo(1)
        assertThat(analysis.beatGrid.beatCount).isEqualTo(43) // 300 ms + k * 468.75 ms < 20 s
        assertThat(analysis.durationMs).isEqualTo(20_000)
        with(analysis.waveform) {
            assertThat(detailSize).isEqualTo(3_000) // 150 columns per second
            assertThat(preview()).hasLength(BlueWaveform.PREVIEW_COLUMNS)
            assertThat(tinyPreview()).hasLength(BlueWaveform.TINY_PREVIEW_COLUMNS)
            // The kicks (0.42 peak after headroom) rise above the chord between them.
            assertThat(detail().maxOf { BlueWaveform.heightOf(it) }).isAtLeast(3)
            assertThat(preview().all { BlueWaveform.heightOf(it) > 0 }).isTrue()
        }
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
        file.delete()
    }

    /** Informal performance figure: wall time for a 6-minute stereo track (logged under [PERF_TAG]). */
    @Test
    fun analysesASixMinuteTrack() {
        val file = clickTrackWav("click_128_a_minor_6min.wav", seconds = 360.0)

        val start = SystemClock.elapsedRealtime()
        val analysis = runBlocking { analyzer.analyze(Uri.fromFile(file).toString()) }
        val elapsedMs = SystemClock.elapsedRealtime() - start

        Log.i(PERF_TAG, "6-minute 44.1 kHz stereo track analysed in $elapsedMs ms (bpm=${analysis.bpm}, key=${analysis.key?.alphanumeric})")
        assertThat(analysis.bpm).isWithin(0.01).of(128.0)
        assertThat(analysis.key).isEqualTo(MusicalKey.A_MINOR)
        file.delete()
    }

    @Test
    fun nativeAnalyzerReportsTypedFailuresAndClosesIdempotently() {
        assertThrows(AnalysisException.UnsupportedSampleRate::class.java) { NativeAnalyzer.create(4_000) }

        val engine = NativeAnalyzer.create(44_100)
        engine.process(FloatArray(44_100), 44_100)
        val tooShort = assertThrows(AnalysisException.TooShort::class.java) { engine.finish() }
        assertThat(tooShort.durationMs).isEqualTo(1_000)
        engine.close()
        engine.close()
        assertThrows(IllegalStateException::class.java) { engine.process(FloatArray(1), 1) }
    }

    /**
     * Writes a 16-bit stereo 44.1 kHz WAV: an A minor triad, and from 0.3 s on a kick on every beat at 128 BPM, a
     * noise burst on every downbeat and a bass note (A2 / E2) that changes on every bar line. Same signal as the
     * host tests in src/test/cpp/analyzer_test.cpp.
     */
    private fun clickTrackWav(name: String, seconds: Double): File {
        val file = File(context.cacheDir, name)
        val frames = (SAMPLE_RATE * seconds).toInt()
        val dataBytes = frames * CHANNELS * 2
        val beatLength = 60.0 / BPM * SAMPLE_RATE
        val offset = OFFSET_SECONDS * SAMPLE_RATE
        var seed = 1
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            out.write(wavHeader(dataBytes))
            val block = ByteBuffer.allocate(4096 * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                val t = i.toDouble() / SAMPLE_RATE
                var v = CHORD.sumOf { 0.08 * sin(2 * PI * it * t) }
                if (i >= offset) {
                    val position = (i - offset) / beatLength
                    val beat = position.toLong()
                    val dt = (position - beat) * beatLength / SAMPLE_RATE
                    v += 0.6 * exp(-dt * 30) * sin(2 * PI * 60 * dt)
                    v += 0.15 * sin(2 * PI * BASS[((beat / 4) % 2).toInt()] * t)
                    if (beat % 4 == 0L) {
                        seed = seed * 1664525 + 1013904223
                        v += 0.3 * exp(-dt * 8) * ((seed ushr 9) / 4194304.0 - 1.0)
                    }
                }
                val sample = (v * HEADROOM * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
                repeat(CHANNELS) { block.putShort(sample) }
                if (!block.hasRemaining()) {
                    out.write(block.array(), 0, block.position())
                    block.clear()
                }
            }
            out.write(block.array(), 0, block.position())
        }
        return file
    }

    private fun wavHeader(dataBytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort()).putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * CHANNELS * 2).putShort((CHANNELS * 2).toShort()).putShort(16)
        put("data".toByteArray()).putInt(dataBytes)
    }.array()

    private companion object {
        const val PERF_TAG = "SonoraAnalysisPerf"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BPM = 128.0
        const val OFFSET_SECONDS = 0.3
        /** Keeps the summed parts (peak ~1.3) clear of 16-bit clipping. */
        const val HEADROOM = 0.7
        val CHORD = doubleArrayOf(220.0, 261.63, 329.63)
        val BASS = doubleArrayOf(110.0, 82.41)
    }
}
