package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class DjAnalysisRunnerTest {

    private val uri = "content://test/track.flac"
    private val id = audioId(7)
    private val store = FakeStore()
    private val passes = FakePasses()
    private val log = RecordingLog()
    private val clock = SteppingClock()
    private val runner = DjAnalysisRunner({ id }, store, passes, log, debuggable = false, monotonicNanos = clock::read)

    @Test
    fun `identify gives the identifier's id`() = runBlocking<Unit> {
        assertThat(runner.identify(uri)).isEqualTo(id)
    }

    @Test
    fun `a track without a stored analysis is not analysed`() = runBlocking<Unit> {
        assertThat(runner.isAnalysed(id)).isFalse()
    }

    @Test
    fun `a complete stored analysis is analysed`() = runBlocking<Unit> {
        store.rows[id] = analysis()

        assertThat(runner.isAnalysed(id)).isTrue()
    }

    @Test
    fun `an analysis restored without its waveform is not analysed yet`() = runBlocking<Unit> {
        store.rows[id] = analysis(waveform = BandWaveform.MISSING)

        assertThat(runner.isAnalysed(id)).isFalse()
    }

    @Test
    fun `a new track gets the full pass, stored if absent, and one telemetry line`() = runBlocking<Unit> {
        runner.identify(uri)

        runner.analyze(uri, id) { _, _ -> }

        assertThat(passes.fullRuns).isEqualTo(1)
        assertThat(passes.waveformRuns).isEqualTo(0)
        assertThat(store.rows[id]).isEqualTo(passes.fullResult)
        assertThat(store.putCalls).isEqualTo(1)
        assertThat(log.infos).containsExactly(
            "analysed id=ap1:000000000000 kind=full audio=1.0s total=74ms speed=13.5x source=beatthis-small0/v " +
                "accelerator=CPU stored=true debuggable=false IDENTIFY=1 DECODE=50 INFERENCE=20 KEY=2 STORE=1",
        )
    }

    @Test
    fun `an analysis that appeared meanwhile is kept, never replaced`() = runBlocking<Unit> {
        val first = analysis(durationMs = 999)
        store.rows[id] = first
        passes.beforeFullReturns = {}

        runner.analyze(uri, id) { _, _ -> }

        assertThat(store.rows[id]).isSameInstanceAs(first)
        assertThat(store.replaceCalls).isEqualTo(0)
        assertThat(log.infos.single()).contains("stored=false")
    }

    @Test
    fun `a restored analysis gets only its waveform, keeping grid and key`() = runBlocking<Unit> {
        val restored = analysis(waveform = BandWaveform.MISSING, durationMs = 1_000)
        store.rows[id] = restored

        runner.analyze(uri, id) { _, _ -> }

        assertThat(passes.fullRuns).isEqualTo(0)
        assertThat(passes.waveformRuns).isEqualTo(1)
        val stored = store.rows[id]!!
        assertThat(stored.waveform).isEqualTo(passes.waveformResult)
        assertThat(stored.grid).isEqualTo(restored.grid)
        assertThat(stored.key).isEqualTo(restored.key)
        assertThat(stored.provenance).isEqualTo(restored.provenance)
        assertThat(log.infos.single()).startsWith("analysed id=ap1:000000000000 kind=waveform_only audio=1.0s")
        assertThat(log.infos.single()).contains("source=none accelerator=none stored=true")
    }

    @Test
    fun `a re-analysis runs the full pass and replaces the stored analysis`() = runBlocking<Unit> {
        store.rows[id] = analysis(durationMs = 999)
        runner.identify(uri)

        runner.reanalyze(uri, id) { _, _ -> }

        assertThat(passes.fullRuns).isEqualTo(1)
        assertThat(store.rows[id]).isEqualTo(passes.fullResult)
        assertThat(store.replaceCalls).isEqualTo(1)
        assertThat(store.putCalls).isEqualTo(0)
        assertThat(log.infos.single()).startsWith("analysed id=ap1:000000000000 kind=reanalysis audio=1.0s")
        assertThat(log.infos.single()).contains("stored=true")
    }

    @Test
    fun `a re-analysis of a restored analysis without a waveform also runs the full pass`() = runBlocking<Unit> {
        store.rows[id] = analysis(waveform = BandWaveform.MISSING)

        runner.reanalyze(uri, id) { _, _ -> }

        assertThat(passes.fullRuns).isEqualTo(1)
        assertThat(passes.waveformRuns).isEqualTo(0)
        assertThat(store.rows[id]).isEqualTo(passes.fullResult)
    }

    @Test
    fun `a re-analysis stores a result without a grid edit, which lives apart`() = runBlocking<Unit> {
        store.rows[id] = analysis()

        runner.reanalyze(uri, id) { _, _ -> }

        assertThat(store.rows[id]!!.gridEdit).isNull()
    }

    @Test
    fun `a re-analysis that fails keeps the stored analysis`() {
        val kept = analysis(durationMs = 999)
        store.rows[id] = kept
        val failure = IllegalStateException("decoder exploded")
        passes.failure = failure

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { runner.reanalyze(uri, id) { _, _ -> } } }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(store.rows[id]).isSameInstanceAs(kept)
        assertThat(store.replaceCalls).isEqualTo(0)
        assertThat(log.infos).isEmpty()
        assertThat(log.warnings.single().second).isSameInstanceAs(failure)
    }

    @Test
    fun `a cancelled re-analysis keeps the stored analysis and is not logged`() {
        val kept = analysis(durationMs = 999)
        store.rows[id] = kept
        passes.failure = CancellationException("pre-empted")

        assertThrows(CancellationException::class.java) { runBlocking { runner.reanalyze(uri, id) { _, _ -> } } }

        assertThat(store.rows[id]).isSameInstanceAs(kept)
        assertThat(log.infos).isEmpty()
        assertThat(log.warnings).isEmpty()
    }

    @Test
    fun `progress is forwarded`() = runBlocking<Unit> {
        val steps = ArrayList<Pair<AnalysisStage, Float>>()

        runner.analyze(uri, id) { stage, fraction -> steps += stage to fraction }

        assertThat(steps).containsExactly(AnalysisStage.DECODE to 0.5f, AnalysisStage.GRID to 1f).inOrder()
    }

    @Test
    fun `a failing pass is logged, thrown and stores nothing`() {
        val failure = IllegalStateException("decoder exploded")
        passes.failure = failure

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { runner.analyze(uri, id) { _, _ -> } } }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(store.rows).isEmpty()
        assertThat(log.infos).isEmpty()
        assertThat(log.warnings.single().second).isSameInstanceAs(failure)
    }

    @Test
    fun `a cancelled pass is thrown and not logged`() {
        passes.failure = CancellationException("left the window")

        assertThrows(CancellationException::class.java) { runBlocking { runner.analyze(uri, id) { _, _ -> } } }
        assertThat(log.infos).isEmpty()
        assertThat(log.warnings).isEmpty()
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `a failing identification is thrown`() {
        val failing = DjAnalysisRunner({ throw java.io.FileNotFoundException(it) }, store, passes, log, false, clock::read)

        assertThrows(java.io.FileNotFoundException::class.java) { runBlocking { failing.identify(uri) } }
    }

    private class FakePasses : AnalysisPasses {
        var fullRuns = 0
        var waveformRuns = 0
        var failure: Throwable? = null
        var beforeFullReturns: () -> Unit = {}
        val fullResult: DjAnalysis = analysis(durationMs = 1_000)
        val waveformResult: BandWaveform = waveform(150, level = 42)

        override suspend fun full(
            sourceUri: String,
            audioId: AudioId,
            onProgress: (AnalysisStage, Float) -> Unit,
        ): PassResult<DjAnalysis> {
            fullRuns++
            failure?.let { throw it }
            onProgress(AnalysisStage.DECODE, 0.5f)
            onProgress(AnalysisStage.GRID, 1f)
            beforeFullReturns()
            return PassResult(
                fullResult,
                linkedMapOf(AnalysisStage.DECODE to 50L, AnalysisStage.INFERENCE to 20L, AnalysisStage.KEY to 2L),
                audioMs = 1_000,
                provenance = AnalysisProvenance(1, BeatSource.MODEL, "beatthis-small0/v", Accelerator.CPU, 0),
            )
        }

        override suspend fun waveform(sourceUri: String, onProgress: (AnalysisStage, Float) -> Unit): PassResult<BandWaveform> {
            waveformRuns++
            failure?.let { throw it }
            return PassResult(waveformResult, linkedMapOf(AnalysisStage.DECODE to 30L, AnalysisStage.WAVEFORM to 5L), audioMs = 1_000)
        }
    }
}
