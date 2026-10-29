package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Test

class GridFitterTest {

    private val durationMs = 120_000.0

    /** Beats of a constant tempo from [firstMs]; every [barLength]-th one from [downbeatIndex] is a downbeat. */
    private class Series(val beats: List<Double>, val downbeats: List<Double>)

    private fun series(
        bpm: Double,
        firstMs: Double,
        durationMs: Double,
        jitterMs: Double = 0.0,
        seed: Long = 1,
        downbeatIndex: Int = 0,
    ): Series {
        val random = Random(seed)
        val interval = 60_000.0 / bpm
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var n = 0
        while (true) {
            val time = firstMs + n * interval
            if (time >= durationMs - interval) break
            val jittered = time + (random.nextDouble() * 2 - 1) * jitterMs
            if (jittered >= 0) {
                beats += jittered
                if (Math.floorMod(n - downbeatIndex, 4) == 0) downbeats += jittered
            }
            n++
        }
        return Series(beats, downbeats)
    }

    private fun candidates(s: Series) = BeatCandidates(s.beats.toDoubleArray(), s.downbeats.toDoubleArray())

    private fun fit(s: Series, prior: TempoPrior = TempoPrior.DEFAULT, duration: Double = durationMs): DjBeatGrid? =
        GridFitter.fitConstant(candidates(s), duration, prior)

    /** Adds a detection half a beat after every beat (the model firing twice per click) and returns the merged series. */
    private fun withHalfBeats(s: Series, bpm: Double, jitterMs: Double, seed: Long): Series {
        val random = Random(seed)
        val half = 30_000.0 / bpm
        val merged = (s.beats + s.beats.map { it + half + (random.nextDouble() * 2 - 1) * jitterMs }).sorted()
        return Series(merged, s.downbeats)
    }

    private fun DjBeatGrid.bpm() = segments.single().bpm

    @Test
    fun `jittered 128 BPM beats give exactly 128 BPM`() {
        val grid = fit(series(128.0, 250.0, durationMs, jitterMs = 10.0))!!

        assertThat(grid.isConstant).isTrue()
        assertThat(grid.bpm()).isEqualTo(128.0)
    }

    @Test
    fun `a tempo that is not a whole number is kept to 0_01`() {
        val grid = fit(series(127.43, 180.0, 180_000.0, jitterMs = 10.0), duration = 180_000.0)!!

        assertThat(grid.bpm()).isEqualTo(127.43)
    }

    @Test
    fun `slow and fast fractional tempos keep their hundredths`() {
        assertThat(fit(series(87.65, 90.0, durationMs, jitterMs = 8.0, seed = 3))!!.bpm()).isEqualTo(87.65)
        assertThat(fit(series(173.21, 400.0, durationMs, jitterMs = 8.0, seed = 4))!!.bpm()).isEqualTo(173.21)
    }

    @Test
    fun `a tempo whose drift stays small is snapped to the whole number`() {
        // 128.01 over two minutes drifts 0.03 ms per beat, 8 ms at most: snapped.
        val s = series(128.004, 120.0, durationMs, jitterMs = 6.0, seed = 5)

        assertThat(fit(s)!!.bpm()).isEqualTo(128.0)
    }

    @Test
    fun `the snap tolerance is an option`() {
        val s = series(128.05, 120.0, durationMs)

        val strict = GridFitter.fitConstant(candidates(s), durationMs, TempoPrior.DEFAULT, GridFitOptions(snapToleranceMs = 0.5))
        val loose = GridFitter.fitConstant(candidates(s), durationMs, TempoPrior.DEFAULT, GridFitOptions(snapToleranceMs = 50.0))

        assertThat(strict!!.bpm()).isEqualTo(128.05)
        assertThat(loose!!.bpm()).isEqualTo(128.0)
    }

    @Test
    fun `doubled detections every half beat still give the true tempo`() {
        val clean = series(128.0, 250.0, durationMs, jitterMs = 8.0, seed = 6)
        val doubled = withHalfBeats(clean, 128.0, jitterMs = 8.0, seed = 7)

        val grid = fit(doubled)!!

        assertThat(grid.bpm()).isEqualTo(128.0)
        // The phase follows the real clicks, not the half-beat detections.
        val first = grid.segments.single().startMs
        assertThat(abs(first - 250.0 % (60_000.0 / 128.0))).isLessThan(8.0)
    }

    @Test
    fun `doubled detections of a slow tempo keep the slow tempo`() {
        // 90 BPM doubled looks like 180 BPM, which is also inside the range: the preference and the bars say 90.
        val clean = series(90.0, 300.0, durationMs, jitterMs = 6.0, seed = 8)
        val doubled = withHalfBeats(clean, 90.0, jitterMs = 6.0, seed = 9)

        assertThat(fit(doubled)!!.bpm()).isEqualTo(90.0)
    }

    @Test
    fun `doubled detections without downbeats also keep the tempo the preference prefers`() {
        val clean = series(100.0, 300.0, durationMs, jitterMs = 6.0, seed = 10)
        val doubled = withHalfBeats(Series(clean.beats, emptyList()), 100.0, jitterMs = 6.0, seed = 11)

        assertThat(fit(doubled)!!.bpm()).isEqualTo(100.0)
    }

    @Test
    fun `twin detections a few milliseconds after each click do not change the tempo`() {
        val clean = series(120.0, 200.0, durationMs, jitterMs = 5.0, seed = 12)
        val twins = Series((clean.beats + clean.beats.map { it + 25.0 }).sorted(), clean.downbeats)

        assertThat(fit(twins)!!.bpm()).isEqualTo(120.0)
    }

    @Test
    fun `detections near 240 BPM fold to 120 with the default prior`() {
        val grid = fit(series(240.0, 100.0, durationMs, jitterMs = 5.0, seed = 13))!!

        assertThat(grid.bpm()).isEqualTo(120.0)
    }

    @Test
    fun `detections near 60 BPM fold to 120 with the default prior`() {
        val grid = fit(series(60.0, 100.0, durationMs, jitterMs = 5.0, seed = 14))!!

        assertThat(grid.bpm()).isEqualTo(120.0)
    }

    @Test
    fun `the range of the prior is an explicit parameter`() {
        val s = series(120.0, 100.0, durationMs, jitterMs = 4.0, seed = 15)

        val fast = fit(s, TempoPrior(minBpm = 150.0, maxBpm = 300.0))
        val slow = fit(s, TempoPrior(minBpm = 40.0, maxBpm = 80.0))

        assertThat(fast!!.bpm()).isEqualTo(240.0)
        assertThat(slow!!.bpm()).isEqualTo(60.0)
    }

    @Test
    fun `spurious detections and missing beats are rejected`() {
        val clean = series(124.0, 330.0, durationMs, jitterMs = 6.0, seed = 16)
        val random = Random(17)
        val kept = clean.beats.filterIndexed { i, _ -> i % 9 != 4 && i % 17 != 3 }
        val noise = List(kept.size / 4) { random.nextDouble() * (durationMs - 1000) + 500 }
        val all = (kept + noise).sorted().fold(ArrayList<Double>()) { acc, t ->
            if (acc.isEmpty() || t - acc.last() > 1.0) acc += t
            acc
        }

        val grid = GridFitter.fitConstant(BeatCandidates(all.toDoubleArray(), clean.downbeats.toDoubleArray()), durationMs)!!

        assertThat(grid.bpm()).isEqualTo(124.0)
        val start = grid.segments.single().startMs
        val expected = 330.0 % (60_000.0 / 124.0)
        assertThat(abs(start - expected)).isLessThan(4.0)
    }

    @Test
    fun `a stretch without detections does not break the fit`() {
        val clean = series(110.0, 200.0, durationMs, jitterMs = 5.0, seed = 18)
        val gap = clean.beats.filter { it < 40_000.0 || it > 70_000.0 }

        val grid = GridFitter.fitConstant(BeatCandidates(gap.toDoubleArray(), DoubleArray(0)), durationMs)!!

        assertThat(grid.bpm()).isEqualTo(110.0)
    }

    @Test
    fun `the downbeat phase is carried to the first beat of the grid`() {
        // Beat 0 is at 40 ms and the downbeat is beat 2, so the first beat sits at position 3 of its bar.
        val s = series(120.0, 40.0, 30_000.0, downbeatIndex = 2)

        val segment = fit(s, duration = 30_000.0)!!.segments.single()

        assertThat(segment.startMs).isWithin(1.0).of(40.0)
        assertThat(segment.beatInBarAtStart).isEqualTo(3)
    }

    @Test
    fun `the bar phase follows the first beat at or after zero`() {
        // 120 BPM from 1300 ms: the grid starts at 300 ms (two beats before 1300); the downbeat is the beat at 1300.
        val s = series(120.0, 1300.0, 30_000.0, downbeatIndex = 0)

        val grid = fit(s, duration = 30_000.0)!!
        val beats = grid.beats(30_000.0)

        assertThat(grid.segments.single().startMs).isWithin(1.0).of(300.0)
        assertThat(beats.first { it.timeMs > 1299.0 }.beatInBar).isEqualTo(1)
        assertThat(beats.first().beatInBar).isEqualTo(3)
    }

    @Test
    fun `downbeats that disagree with the grid are ignored and the vote goes to the majority phase`() {
        val s = series(120.0, 0.0, 40_000.0, downbeatIndex = 1)
        val stray = (s.downbeats + listOf(3_300.0, 15_050.0)).sorted()

        val segment = GridFitter.fitConstant(BeatCandidates(s.beats.toDoubleArray(), stray.toDoubleArray()), 40_000.0)!!
            .segments.single()

        // Beat 0 is beat 4 of the bar before the downbeat at beat 1.
        assertThat(segment.beatInBarAtStart).isEqualTo(4)
    }

    @Test
    fun `downbeat probabilities decide the bar phase when the downbeat list marks most beats`() {
        // The small model marks most plain clicks as downbeats; only the probabilities tell the bar start.
        val s = series(120.0, 40.0, 30_000.0)
        val probabilities = DoubleArray(s.beats.size) { if (it % 4 == 2) 0.9 else 0.3 }

        val grid = GridFitter.fitConstant(BeatCandidates(s.beats.toDoubleArray(), s.beats.toDoubleArray(), probabilities), 30_000.0)!!

        assertThat(grid.segments.single().beatInBarAtStart).isEqualTo(3)
    }

    @Test
    fun `unknown probabilities fall back to the downbeat list`() {
        val s = series(120.0, 40.0, 30_000.0, downbeatIndex = 1)
        val unknown = DoubleArray(s.beats.size) { Double.NaN }

        val grid = GridFitter.fitConstant(BeatCandidates(s.beats.toDoubleArray(), s.downbeats.toDoubleArray(), unknown), 30_000.0)!!

        assertThat(grid.segments.single().beatInBarAtStart).isEqualTo(4)
    }

    @Test
    fun `downbeats on every click do not move the octave`() {
        val clean = series(90.0, 300.0, durationMs, jitterMs = 6.0, seed = 21)
        val doubled = withHalfBeats(clean, 90.0, jitterMs = 6.0, seed = 22)

        val grid = GridFitter.fitConstant(BeatCandidates(doubled.beats.toDoubleArray(), clean.beats.toDoubleArray()), durationMs)!!

        assertThat(grid.bpm()).isEqualTo(90.0)
    }

    @Test
    fun `without downbeats the first beat is the downbeat`() {
        val s = Series(series(120.0, 70.0, 30_000.0).beats, emptyList())

        assertThat(fit(s, duration = 30_000.0)!!.segments.single().beatInBarAtStart).isEqualTo(1)
    }

    @Test
    fun `too few candidates give no grid`() {
        assertThat(GridFitter.fitConstant(BeatCandidates(DoubleArray(0), DoubleArray(0)), durationMs)).isNull()
        assertThat(GridFitter.fitConstant(BeatCandidates(doubleArrayOf(500.0, 1000.0, 1500.0, 2000.0, 2500.0), DoubleArray(0)), durationMs)).isNull()
    }

    @Test
    fun `candidates outside the track are not counted`() {
        val inside = series(120.0, 100.0, 3_000.0).beats
        val outside = List(20) { 10_000.0 + it * 500.0 }

        val grid = GridFitter.fitConstant(BeatCandidates((inside + outside).toDoubleArray(), DoubleArray(0)), 3_000.0)

        // Five or six beats inside a 3 s track are too few.
        assertThat(grid).isNull()
    }

    @Test
    fun `detections with no regular pulse give no grid`() {
        val random = Random(19)
        var t = 200.0
        val beats = DoubleArray(150) { t += 150 + random.nextDouble() * 1200; t }

        assertThat(GridFitter.fitConstant(BeatCandidates(beats, DoubleArray(0)), t + 1000)).isNull()
    }

    @Test
    fun `phase is within 2 ms on clean input`() {
        for ((bpm, first) in listOf(100.0 to 123.4, 127.43 to 61.7, 140.0 to 1300.9, 90.0 to 5.0, 175.5 to 0.0)) {
            val s = series(bpm, first, durationMs)

            val segment = fit(s)!!.segments.single()
            val interval = 60_000.0 / bpm
            val expected = first - floor(first / interval) * interval

            assertThat(segment.bpm).isEqualTo(bpm)
            assertThat(abs(segment.startMs - expected)).isLessThan(2.0)
        }
    }

    @Test
    fun `the fitted grid reproduces every clean beat within 2 ms`() {
        val s = series(127.43, 300.0, 90_000.0)

        val grid = fit(s, duration = 90_000.0)!!
        val beats = grid.beats(90_000.0).map { it.timeMs }

        for (time in s.beats) {
            val nearest = beats.minOf { abs(it - time) }
            assertThat(nearest).isLessThan(2.0)
        }
    }

    @Test
    fun `the result is a single segment starting at or after zero`() {
        val grid = fit(series(120.0, 5_000.0, durationMs, jitterMs = 3.0, seed = 20))!!

        assertThat(grid.segments).hasSize(1)
        assertThat(grid.segments.single().startMs).isAtLeast(0.0)
        assertThat(grid.segments.single().startMs).isLessThan(500.0 + 1.0)
    }

    @Test
    fun `a jittery intro does not set the tempo of a long steady track`() {
        // Model beats of a 12.5-minute 138 BPM track: the first beats of its intro jitter by 10-20 ms. Fitting the
        // slope on them set the line 1 % slow and it lost the beat within a few bars (coverage 0.21, no grid).
        val (beats, downbeats, probabilities) = resourceCandidates("/beats/steady-138-jittery-intro.csv")

        val grid = GridFitter.fitConstant(BeatCandidates(beats, downbeats, probabilities), durationMs = 749_200.0)

        assertThat(grid).isNotNull()
        assertThat(grid!!.segments).hasSize(1)
        assertThat(grid.segments.single().bpm).isEqualTo(138.0)
    }

    @Test
    fun `a 150 BPM track whose bars agree is not fitted at half tempo`() {
        // Model beats of a 6.6-minute 150 BPM track (rekordbox: 150.00): 473 of 882 gaps are 387-412 ms and the
        // downbeats are a bar of 150 BPM apart, yet the grid came out at 75 BPM.
        val (beats, downbeats, probabilities) = resourceCandidates("/beats/steady-150-half-tempo.csv")

        val grid = GridFitter.fitConstant(BeatCandidates(beats, downbeats, probabilities), durationMs = 397_955.0)

        assertThat(grid).isNotNull()
        assertThat(grid!!.segments.single().bpm).isEqualTo(150.0)
    }

    @Test
    fun `a 178 BPM track with a beat between every slow beat is not fitted at half tempo`() {
        // Model beats of a 4.5-minute 178 BPM track (rekordbox: 178.00): most gaps are 325-350 ms, but the downbeats
        // give no bar length and the tempo prior prefers 89 BPM, whose grid has a detection on every slot.
        val (beats, downbeats, probabilities) = resourceCandidates("/beats/steady-178-half-tempo.csv")

        val grid = GridFitter.fitConstant(BeatCandidates(beats, downbeats, probabilities), durationMs = 269_600.0)

        assertThat(grid).isNotNull()
        assertThat(grid!!.segments.single().bpm).isWithin(0.05).of(178.0)
    }

    private fun resourceCandidates(path: String): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val lines = javaClass.getResourceAsStream(path)!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val beats = lines.filter { it.startsWith("b,") }.map { it.split(',') }
        val downbeats = lines.filter { it.startsWith("d,") }.map { it.split(',')[1].toDouble() }
        return Triple(
            DoubleArray(beats.size) { beats[it][1].toDouble() },
            downbeats.toDoubleArray(),
            DoubleArray(beats.size) { beats[it][2].toDouble() },
        )
    }
}
