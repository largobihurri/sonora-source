package io.sonora.core.analysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BlueWaveform
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertThrows
import org.junit.Test

class BlueWaveformBuilderTest {

    /** A mono signal as a function of the frame index. */
    private fun interface Signal {
        fun at(frame: Long): Float
    }

    private fun sine(frequencyHz: Double, amplitude: Double, sampleRateHz: Int) = Signal { frame ->
        (amplitude * sin(2 * PI * frequencyHz * frame / sampleRateHz)).toFloat()
    }

    private val silence = Signal { 0f }

    /** Feeds [frames] frames of [signal] in blocks of [chunk] frames (the last one shorter), like the analyser does. */
    private fun build(signal: Signal, frames: Long, sampleRateHz: Int = 44_100, chunk: Int = 4096): BlueWaveform {
        val builder = BlueWaveformBuilder(sampleRateHz)
        val buffer = FloatArray(chunk)
        var done = 0L
        while (done < frames) {
            val count = minOf(chunk.toLong(), frames - done).toInt()
            for (i in 0 until count) buffer[i] = signal.at(done + i)
            builder.process(buffer, count)
            done += count
        }
        return builder.finish()
    }

    private fun seconds(s: Double, sampleRateHz: Int = 44_100): Long = (s * sampleRateHz).toLong()

    private fun BlueWaveform.detailHeights() = detail().map { BlueWaveform.heightOf(it) }
    private fun BlueWaveform.detailWhiteness() = detail().map { BlueWaveform.whitenessOf(it) }
    private fun BlueWaveform.previewHeights() = preview().map { BlueWaveform.heightOf(it) }
    private fun BlueWaveform.previewWhiteness() = preview().map { BlueWaveform.whitenessOf(it) }
    private fun BlueWaveform.tinyHeights() = tinyPreview().map { it.toInt() }

    /** Columns away from the edges, where the filters have settled. */
    private fun <T> List<T>.middle(): List<T> = subList(size / 10, size - size / 10)

    @Test
    fun `detail has 150 columns per second at 44_1 and 48 kHz, previews always 400 and 100`() {
        for (rate in listOf(44_100, 48_000)) {
            for (duration in listOf(5.0, 7.3, 61.25, 600.0)) {
                val frames = seconds(duration, rate)
                val waveform = build(silence, frames, rate)
                assertThat(waveform.detailSize).isEqualTo(BlueWaveform.detailColumnCount(frames, rate))
                assertThat(waveform.preview()).hasLength(BlueWaveform.PREVIEW_COLUMNS)
                assertThat(waveform.tinyPreview()).hasLength(BlueWaveform.TINY_PREVIEW_COLUMNS)
            }
        }
        assertThat(build(silence, 44_100L * 600).detailSize).isEqualTo(90_000)
        assertThat(build(silence, 48_000L * 5, 48_000).detailSize).isEqualTo(750)
        assertThat(build(silence, 44_100L * 5 + 1).detailSize).isEqualTo(751)
    }

    @Test
    fun `silence is all zeros`() {
        val waveform = build(silence, seconds(12.0))
        assertThat(waveform.detail().all { it == 0.toByte() }).isTrue()
        assertThat(waveform.preview().all { it == 0.toByte() }).isTrue()
        assertThat(waveform.tinyPreview().all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `a full-scale sine reaches full height, half amplitude a quarter (power curve)`() {
        val full = build(sine(1_000.0, 1.0, 44_100), seconds(6.0))
        val half = build(sine(1_000.0, 0.5, 44_100), seconds(6.0))

        assertThat(full.detailHeights().middle().toSet()).containsExactly(31)
        assertThat(half.detailHeights().middle().toSet()).containsExactly(7)
        // Louder than full scale (float decoders can overshoot) is clamped.
        assertThat(build(sine(1_000.0, 1.5, 44_100), seconds(6.0)).detailHeights().max()).isEqualTo(31)
    }

    @Test
    fun `heights grow with loudness in every waveform`() {
        val amplitudes = listOf(0.05, 0.1, 0.2, 0.35, 0.5, 0.7, 1.0)
        val waveforms = amplitudes.map { build(sine(150.0, it, 44_100), seconds(6.0)) }

        val detail = waveforms.map { it.detailHeights().middle().average() }
        val preview = waveforms.map { it.previewHeights().middle().average() }
        val tiny = waveforms.map { it.tinyHeights().middle().average() }

        assertThat(detail).isInOrder()
        assertThat(preview).isInOrder()
        assertThat(tiny).isInOrder()
        assertThat(detail.first()).isLessThan(detail.last())
        assertThat(preview.first()).isLessThan(preview.last())
        assertThat(tiny.first()).isLessThan(tiny.last())
        // A steady full-scale bass is all loud columns: levelled to 14 (PWV2) and 22 (PWAV), as rekordbox's loud parts.
        assertThat(waveforms.last().tinyHeights().middle().toSet()).containsExactly(14)
        assertThat(waveforms.last().previewHeights().middle().toSet()).containsExactly(22)
    }

    @Test
    fun `previews are calibrated like rekordbox's sweep, with the bass emphasised`() {
        val treble = build(sine(5_000.0, 1.0, 44_100), seconds(10.0))
        val bassHalf = build(sine(150.0, 0.5, 44_100), seconds(10.0))
        val trebleHalf = build(sine(5_000.0, 0.5, 44_100), seconds(10.0))

        // rekordbox: full-scale treble ~17 in PWAV, one channel (-6 dB) ~9, -6 dB bass up to ~24.
        assertThat(treble.previewHeights().middle().toSet()).containsExactly(17)
        assertThat(trebleHalf.previewHeights().middle().toSet()).containsExactly(9)
        assertThat(bassHalf.previewHeights().middle().min()).isAtLeast(20)
        assertThat(bassHalf.previewHeights().max()).isAtMost(25)
        // PWV2 is mostly bass: near zero for treble, near the top for -6 dB bass.
        assertThat(treble.tinyHeights().middle().max()).isAtMost(2)
        assertThat(bassHalf.tinyHeights().middle().min()).isAtLeast(12)
    }

    /**
     * A loud, limited dance master at 128 BPM (kick, offbeat bass, hi-hats and a chord pad through a tanh limiter), in
     * four sections: quiet intro (hats and pad) 0-32 s, drop 32-96 s, break (pad and soft hats) 96-128 s, drop 128-192 s.
     */
    private val danceMaster = Signal { frame ->
        val t = frame / 44_100.0
        val beat = 60.0 / 128
        val sinceBeat = t % beat
        val sinceOffbeat = (t + beat / 2) % beat
        val kick = sin(2 * PI * (50 + 80 * kotlin.math.exp(-sinceBeat * 40)) * sinceBeat) * kotlin.math.exp(-sinceBeat * 7)
        val bass = 0.7 * sin(2 * PI * 55 * t) * (if (sinceBeat >= beat / 2) 1.0 else 0.15)
        val hash = (frame * 2_654_435_761L xor (frame ushr 7)) and 0xFFFF
        val hats = 0.35 * (hash / 32_768.0 - 1) * kotlin.math.exp(-sinceOffbeat * 30)
        val pad = 0.12 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277.18 * t) + sin(2 * PI * 329.63 * t))
        val mix = when {
            t < 32 -> 0.3 * (hats + pad)
            t < 96 -> kick + bass + hats + pad
            t < 128 -> 0.5 * pad + 0.2 * hats
            else -> kick + bass + hats + pad
        }
        (kotlin.math.tanh(2.5 * mix) / kotlin.math.tanh(2.5)).toFloat()
    }

    private val danceMasterFrames = seconds(192.0)

    private fun List<Int>.median() = sorted()[size / 2]
    private fun List<Int>.percentile(p: Int) = sorted()[(size - 1) * p / 100]

    @Test
    fun `a loud master's overview follows its sections instead of saturating`() {
        val waveform = build(danceMaster, danceMasterFrames)
        // 400 columns over 192 s: 0.48 s each. Sections without the columns on their borders.
        val preview = waveform.previewHeights()
        val intro = preview.subList(2, 65)
        val drop = preview.subList(69, 198) + preview.subList(269, 398)
        val pause = preview.subList(202, 265)

        assertThat(preview.max()).isAtMost(24) // rekordbox's demo exports: 24 and 22
        assertThat(drop.count { it >= 24 }).isAtMost(drop.size / 10)
        assertThat(drop.median()).isIn(18..23)
        assertThat(intro.median()).isAtMost(drop.median() - 8)
        assertThat(pause.median()).isAtMost(drop.median() - 8)

        // PWV2: 100 columns of 1.92 s; the break has no bass.
        val tiny = waveform.tinyHeights()
        val tinyDrop = tiny.subList(18, 49) + tiny.subList(68, 99)
        assertThat(tiny.max()).isAtMost(BlueWaveform.MAX_TINY_HEIGHT)
        assertThat(tinyDrop.count { it == BlueWaveform.MAX_TINY_HEIGHT }).isAtMost(tinyDrop.size / 10)
        assertThat(tinyDrop.median()).isIn(11..14)
        assertThat(tiny.subList(1, 16).median()).isAtMost(tinyDrop.median() - 5)
        assertThat(tiny.subList(51, 66).median()).isAtMost(tinyDrop.median() - 5)
    }

    @Test
    fun `a loud master's previews have rekordbox's height distribution`() {
        val waveform = build(danceMaster, danceMasterFrames)
        // rekordbox's demo exports: PWAV min 2, median 18 and 11, p90 22 and 18, max 24 and 22; PWV2 median 13, max 15
        // and 14.
        val preview = waveform.previewHeights()
        // Two thirds of this track are drops, so its median sits nearer the loud columns than the demo tracks'.
        assertThat(preview.min()).isAtMost(5)
        assertThat(preview.median()).isIn(11..21)
        assertThat(preview.percentile(90)).isIn(18..22)
        assertThat(preview.max()).isIn(20..24)
        val tiny = waveform.tinyHeights()
        assertThat(tiny.median()).isIn(9..14)
        assertThat(tiny.max()).isIn(13..15)
    }

    @Test
    fun `whiteness is low for bass and high for treble`() {
        val bass = build(sine(60.0, 0.8, 44_100), seconds(6.0))
        val treble = build(sine(5_000.0, 0.8, 44_100), seconds(6.0))
        val mid = build(sine(200.0, 0.8, 44_100), seconds(6.0))

        assertThat(bass.detailWhiteness().middle().max()).isEqualTo(0)
        assertThat(treble.detailWhiteness().middle().toSet()).containsExactly(7)
        assertThat(mid.detailWhiteness().middle().average()).isWithin(1.0).of(3.5) // the 200 Hz crossover
        assertThat(bass.previewWhiteness().middle().max()).isEqualTo(0)
        assertThat(treble.previewWhiteness().middle().toSet()).containsExactly(5) // rekordbox's PWAV maximum
    }

    @Test
    fun `the output does not depend on the block size`() {
        val signal = Signal { frame ->
            val bass = sin(2 * PI * 80 * frame / 44_100) * (frame / 44_100 % 3) / 3
            val treble = 0.3 * sin(2 * PI * 3_000 * frame / 44_100)
            (bass + treble).toFloat()
        }
        val frames = seconds(70.0) + 123
        val reference = build(signal, frames, chunk = 4096)

        for (chunk in listOf(1, 7, 294, 1000, 65_536)) {
            assertThat(build(signal, frames, chunk = chunk)).isEqualTo(reference)
        }
    }

    @Test
    fun `the same audio at 44_1 and 48 kHz gives the same waveform shape`() {
        val at44 = build(sine(1_000.0, 0.7, 44_100), seconds(8.0, 44_100), 44_100)
        val at48 = build(sine(1_000.0, 0.7, 48_000), seconds(8.0, 48_000), 48_000)

        assertThat(at48.detailSize).isEqualTo(at44.detailSize)
        assertThat(at48.detailHeights().middle().toSet()).isEqualTo(at44.detailHeights().middle().toSet())
        assertThat(at48.detailWhiteness().middle().toSet()).isEqualTo(at44.detailWhiteness().middle().toSet())
        assertThat(at48.previewHeights().middle().toSet()).isEqualTo(at44.previewHeights().middle().toSet())
        assertThat(at48.tinyHeights().middle().toSet()).isEqualTo(at44.tinyHeights().middle().toSet())
    }

    @Test
    fun `previews follow the track over its whole length, also for long tracks`() {
        // 400 s: 200 s of bass, then 200 s of silence.
        val loudFrames = seconds(200.0)
        val bass = sine(150.0, 0.5, 44_100)
        val waveform = build({ frame -> if (frame < loudFrames) bass.at(frame) else 0f }, 2 * loudFrames)

        val preview = waveform.previewHeights()
        assertThat(preview.subList(0, 199).min()).isAtLeast(20)
        assertThat(preview.subList(201, 400).max()).isEqualTo(0)
        val tiny = waveform.tinyHeights()
        assertThat(tiny.subList(0, 49).min()).isAtLeast(12)
        assertThat(tiny.subList(51, 100).max()).isEqualTo(0)
        assertThat(waveform.detailSize).isEqualTo(60_000)
    }

    @Test
    fun `very short input still gives full-size previews`() {
        val waveform = build(sine(5_000.0, 1.0, 44_100), 1_000)
        assertThat(waveform.detailSize).isEqualTo(4)
        // Each of the four detail columns fills a quarter of the previews (the first one includes the filters' start).
        val preview = waveform.preview()
        assertThat(preview.indices.all { preview[it] == preview[it / 100 * 100] }).isTrue()
        assertThat(waveform.previewHeights().toSet()).containsAnyOf(17, 18)
        assertThat(waveform.previewHeights().all { it in 17..18 }).isTrue()
        val tiny = waveform.tinyPreview()
        assertThat(tiny).hasLength(BlueWaveform.TINY_PREVIEW_COLUMNS)
        assertThat(tiny.indices.all { tiny[it] == tiny[it / 25 * 25] }).isTrue()
        assertThat(build(silence, 0).preview().all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `non-finite samples count as silence and do not poison later columns`() {
        val tone = sine(1_000.0, 0.5, 44_100)
        val waveform = build({ frame -> if (frame == 100L) Float.NaN else tone.at(frame) }, seconds(6.0))
        assertThat(waveform.detailHeights().middle().toSet()).containsExactly(7)
    }

    @Test
    fun `finish is single use and rejects later input`() {
        val builder = BlueWaveformBuilder(44_100)
        builder.process(FloatArray(10), 10)
        builder.finish()
        assertThrows(IllegalStateException::class.java) { builder.finish() }
        assertThrows(IllegalStateException::class.java) { builder.process(FloatArray(1), 1) }
        assertThrows(IllegalArgumentException::class.java) { BlueWaveformBuilder(0) }
        assertThrows(IllegalArgumentException::class.java) { BlueWaveformBuilder(44_100).process(FloatArray(1), 2) }
    }

    @Test
    fun `absurdly loud finite samples still give a waveform within the players' limits`() {
        // A float file can hold any finite value; squared, such samples overflow to infinity.
        val waveform = build({ frame -> if (frame % 2 == 0L) Float.MAX_VALUE else -Float.MAX_VALUE }, seconds(6.0))

        assertThat(waveform.detailHeights().max()).isAtMost(BlueWaveform.MAX_HEIGHT)
        assertThat(waveform.preview()).hasLength(BlueWaveform.PREVIEW_COLUMNS)
        assertThat(waveform.tinyPreview().max().toInt()).isAtMost(BlueWaveform.MAX_TINY_HEIGHT)
    }
}

