package io.sonora.core.djengine.pipeline

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.audiodsp.resample.PolyphaseResampler
import io.sonora.core.djengine.DJ_ANALYZER_VERSION
import io.sonora.core.djengine.beats.BeatThisCase
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djengine.key.HpcpSink
import io.sonora.core.djengine.key.KeyClassifier
import io.sonora.core.djengine.waveform.BandWaveformSink
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DjAnalysisPipelineTest {

    private val fallbackGridValue = DjBeatGrid(listOf(TempoSegment(100.0, 125.0, 1)))
    private val modelGridValue = DjBeatGrid(listOf(TempoSegment(50.0, 128.0, 2)))
    private val refinedGridValue = DjBeatGrid(listOf(TempoSegment(55.0, 128.0, 2)))
    private val emptyWaveform = BandWaveform(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), 1)

    /** Fakes for every collaborator; [build] makes the pipeline with them. */
    private inner class Rig(
        val rate: Int = 44_100,
        model: () -> BeatActivationModel? = { null },
        val policy: FallbackPolicy = FallbackPolicy.ALWAYS,
        val melFrames: Int = 400,
        withOnset: Boolean = false,
    ) {
        val telemetry = RecordingTelemetry()
        val resampler = HoldResampler()
        val factory = FakeModelFactory(model)
        val waveform = CountingSinks(rate) { emptyWaveform }
        val chroma = CountingSinks(rate) { ChromaFrames(FloatArray(0), 10.0) }
        val mel = CountingSinks(22_050) { melOf(melFrames) }
        val fallback = CountingSinks<DjBeatGrid?>(rate) { fallbackGridValue }
        val onset = if (withOnset) CountingSinks(rate) { OnsetEnvelope(FloatArray(100), 100.0) } else null
        val fitted = ArrayList<Double>()
        val refined = ArrayList<Pair<DjBeatGrid, OnsetEnvelope>>()

        fun build(blockFrames: Int = 1_000): DjAnalysisPipeline = DjAnalysisPipeline(
            modelFactory = factory,
            fallbackGrid = fallback.asStage(),
            resampler = resampler,
            telemetry = telemetry,
            clock = SteppingClock(),
            waveform = waveform.asStage(),
            chroma = chroma.asStage(),
            mel = mel.asStage(),
            onset = onset?.asStage(),
            gridFit = GridFit { _, durationMs -> fitted += durationMs; modelGridValue },
            phaseRefinement = PhaseRefinement { grid, envelope -> refined += grid to envelope; refinedGridValue },
            keyDetection = KeyDetection { null },
            fallbackPolicy = policy,
            blockFrames = blockFrames,
        )
    }

    private fun stereoSource(seconds: Double, rate: Int = 44_100, restartable: Boolean = false, lengthKnown: Boolean = true): ArrayPcmSource {
        val frames = (seconds * rate).toInt()
        val left = FloatArray(frames) { (it % 100) / 200f }
        val right = FloatArray(frames) { -(it % 37) / 100f }
        return ArrayPcmSource(interleave(listOf(left, right)), rate, 2, lengthKnown = lengthKnown, restartable = restartable)
    }

    /** The sum of the mono mix (mean of the two channels) of [stereoSource]. */
    private fun monoSum(frames: Int): Double {
        var sum = 0.0
        for (i in 0 until frames) sum += ((i % 100) / 200f - (i % 37) / 100f) / 2.0
        return sum
    }

    private fun run(pipeline: DjAnalysisPipeline, source: PcmSource, progress: MutableList<Float>? = null, check: () -> Unit = {}): DjAnalysis =
        runBlocking { pipeline.run(source, TEST_AUDIO_ID, { progress?.add(it) }, check) }

    private fun failureOf(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }

    // One decode

    @Test
    fun `one decode feeds every sink every downmixed sample`() {
        val model = FakeModel()
        val rig = Rig(model = { model }, withOnset = true)
        val source = stereoSource(2.0)
        run(rig.build(blockFrames = 1_000), source)

        val frames = source.frames
        val blocks = (frames + 999) / 1_000
        assertThat(source.passes).isEqualTo(1)
        assertThat(source.reads).isEqualTo(blocks + 1)
        for ((name, sinks) in listOf("waveform" to rig.waveform, "chroma" to rig.chroma, "fallback" to rig.fallback, "onset" to rig.onset!!)) {
            assertWithMessage("$name created").that(sinks.created).isEqualTo(1)
            assertWithMessage("$name blocks").that(sinks.blocks).isEqualTo(blocks)
            assertWithMessage("$name samples").that(sinks.samples).isEqualTo(frames.toLong())
            assertWithMessage("$name downmix").that(sinks.sum).isWithin(1e-3).of(monoSum(frames))
        }
        assertThat(rig.resampler.opened).isEqualTo(1)
        assertThat(rig.mel.created).isEqualTo(1)
        assertThat(rig.mel.blocks).isEqualTo(blocks)
        assertThat(rig.mel.samples).isEqualTo(frames / 2L)
        assertThat(model.calls).isGreaterThan(0)
    }

    @Test
    fun `every sink that is used is finished once and the unused fallback is not`() {
        val model = FakeModel()
        val rig = Rig(model = { model }, withOnset = true)
        run(rig.build(), stereoSource(1.0))

        assertThat(rig.waveform.finished).isEqualTo(1)
        assertThat(rig.chroma.finished).isEqualTo(1)
        assertThat(rig.mel.finished).isEqualTo(1)
        assertThat(rig.onset!!.finished).isEqualTo(1)
        assertThat(rig.fallback.finished).isEqualTo(0)
        assertThat(model.closed).isEqualTo(1)
    }

    @Test
    fun `stages at the source rate are not resampled`() {
        val rig = Rig(rate = 22_050, model = { FakeModel() })
        val source = stereoSource(1.0, rate = 22_050)
        val pipeline = DjAnalysisPipeline(
            modelFactory = rig.factory,
            fallbackGrid = rig.fallback.asStage(),
            resampler = UnusedResampler,
            telemetry = rig.telemetry,
            clock = SteppingClock(),
            waveform = rig.waveform.asStage(),
            chroma = rig.chroma.asStage(),
            mel = rig.mel.asStage(),
        )
        run(pipeline, source)
        assertThat(rig.mel.samples).isEqualTo(source.frames.toLong())
    }

    @Test
    fun `stages at two other rates get one resampled stream each`() {
        val rig = Rig(model = { FakeModel() })
        val onset16k = CountingSinks(16_000) { OnsetEnvelope(FloatArray(10), 500.0) }
        val mel22k = CountingSinks(22_050) { melOf(100) }
        val pipeline = DjAnalysisPipeline(
            modelFactory = rig.factory,
            fallbackGrid = rig.fallback.asStage(),
            resampler = rig.resampler,
            telemetry = rig.telemetry,
            clock = SteppingClock(),
            waveform = rig.waveform.asStage(),
            chroma = rig.chroma.asStage(),
            mel = mel22k.asStage(),
            onset = onset16k.asStage(),
            // The onset sink is only fed when a refinement is configured (none by default since D1.20).
            phaseRefinement = DjAnalysisPipeline.DEFAULT_PHASE_REFINEMENT,
        )
        val source = stereoSource(1.0)
        run(pipeline, source)

        assertThat(rig.resampler.opened).isEqualTo(2)
        assertThat(mel22k.samples).isEqualTo(22_050L)
        assertThat(onset16k.samples).isEqualTo(16_000L)
        assertThat(rig.waveform.samples).isEqualTo(44_100L)
    }

    // Beat source

    @Test
    fun `a model grid carries the model id and accelerator`() {
        val rig = Rig(model = { FakeModel() })
        val analysis = run(rig.build(), stereoSource(1.0))

        assertThat(analysis.grid).isEqualTo(modelGridValue)
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.MODEL)
        assertThat(analysis.provenance.modelId).isEqualTo("beatthis-small/1")
        assertThat(analysis.provenance.beatSourceId).isEqualTo("beatthis-small/1")
        assertThat(analysis.provenance.accelerator).isEqualTo(Accelerator.CPU)
        assertThat(analysis.provenance.analyzerVersion).isEqualTo(DJ_ANALYZER_VERSION)
        assertThat(analysis.provenance.analysedAtEpochMs).isEqualTo(SteppingClock.EPOCH_MS)
        assertThat(rig.fitted.single()).isWithin(1e-9).of(1_000.0)
    }

    @Test
    fun `no model gives the fallback grid and the qmdsp source without computing the mel`() {
        val rig = Rig(model = { null })
        val source = stereoSource(1.0)
        val analysis = run(rig.build(), source)

        assertThat(analysis.grid).isEqualTo(fallbackGridValue)
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        assertThat(analysis.provenance.modelId).isNull()
        assertThat(analysis.provenance.accelerator).isNull()
        assertThat(analysis.provenance.beatSourceId).isEqualTo("qmdsp/1")
        assertThat(rig.fallback.finished).isEqualTo(1)
        assertThat(rig.mel.created).isEqualTo(0)
        assertThat(rig.resampler.opened).isEqualTo(0)
        assertThat(source.passes).isEqualTo(1)
        assertThat(rig.telemetry.failed).isEmpty()
    }

    @Test
    fun `a model that throws during inference falls back to the grid fed in the same decode`() {
        val failure = IllegalStateException("model broke")
        val model = FakeModel(answer = { _, _ -> throw failure })
        val rig = Rig(model = { model })
        val source = stereoSource(1.0, restartable = true)
        val analysis = run(rig.build(), source)

        assertThat(analysis.grid).isEqualTo(fallbackGridValue)
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        assertThat(analysis.provenance.beatSourceId).isEqualTo("qmdsp/1")
        assertThat(source.passes).isEqualTo(1)
        assertThat(source.restartRequests).isEqualTo(0)
        assertThat(rig.telemetry.failed).containsExactly(AnalysisStage.INFERENCE to failure)
        assertThat(rig.telemetry.analysed).hasSize(1)
        assertThat(model.closed).isEqualTo(1)
    }

    @Test
    fun `a model factory that throws falls back like a missing model`() {
        val failure = IllegalStateException("cannot load")
        val rig = Rig(model = { throw failure })
        val analysis = run(rig.build(), stereoSource(1.0))

        assertThat(analysis.grid).isEqualTo(fallbackGridValue)
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        assertThat(rig.mel.created).isEqualTo(0)
        assertThat(rig.telemetry.failed).containsExactly(AnalysisStage.INFERENCE to failure)
    }

    @Test
    fun `with the fallback only without a model a failing model costs one re-decode`() {
        val model = FakeModel(answer = { _, _ -> throw IllegalStateException("model broke") })
        val rig = Rig(model = { model }, policy = FallbackPolicy.ONLY_WITHOUT_MODEL)
        val source = stereoSource(1.0, restartable = true)
        val analysis = run(rig.build(), source)

        assertThat(analysis.grid).isEqualTo(fallbackGridValue)
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        assertThat(source.passes).isEqualTo(2)
        assertThat(rig.fallback.created).isEqualTo(1)
        assertThat(rig.fallback.samples).isEqualTo(source.frames.toLong())
        assertThat(rig.waveform.samples).isEqualTo(source.frames.toLong())
    }

    @Test
    fun `with the fallback only without a model and a source that cannot restart the failure is thrown`() {
        val failure = IllegalStateException("model broke")
        val rig = Rig(model = { FakeModel(answer = { _, _ -> throw failure }) }, policy = FallbackPolicy.ONLY_WITHOUT_MODEL)
        val source = stereoSource(1.0, restartable = false)
        val thrown = failureOf { rig.build().run(source, TEST_AUDIO_ID, {}, {}) }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(source.restartRequests).isEqualTo(1)
        assertThat(rig.fallback.created).isEqualTo(0)
        assertThat(rig.telemetry.failed).containsExactly(AnalysisStage.INFERENCE to failure)
        assertThat(rig.telemetry.analysed).isEmpty()
    }

    @Test
    fun `with the fallback only without a model a model that works needs no fallback sink`() {
        val rig = Rig(model = { FakeModel() }, policy = FallbackPolicy.ONLY_WITHOUT_MODEL)
        val analysis = run(rig.build(), stereoSource(1.0))
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.MODEL)
        assertThat(rig.fallback.created).isEqualTo(0)
    }

    // Key, waveform, refinement

    @Test
    fun `key and waveform come from the default sinks at the source rate`() {
        val audio = SyntheticAudio.diatonicProgression(MusicalKey.A_MINOR, chordMs = 2_000.0, sampleRateHz = 48_000)
        val source = ArrayPcmSource(audio, 48_000)
        val telemetry = RecordingTelemetry()
        val pipeline = DjAnalysisPipeline(
            modelFactory = FakeModelFactory { null },
            fallbackGrid = CountingSinks<DjBeatGrid?>(48_000) { null }.asStage(),
            resampler = UnusedResampler,
            telemetry = telemetry,
            clock = SteppingClock(),
            waveform = StageSinks { BandWaveformSink(it) },
            chroma = StageSinks { HpcpSink(it) },
        )
        val analysis = run(pipeline, source)

        val directWaveform = BandWaveformSink(48_000).create().apply { accept(audio, 0, audio.size) }.finish()
        val directChroma = HpcpSink(48_000).create().apply { accept(audio, 0, audio.size) }.finish()
        assertThat(analysis.waveform).isEqualTo(directWaveform)
        assertThat(analysis.key).isNotNull()
        assertThat(analysis.key).isEqualTo(KeyClassifier.classify(directChroma))
        assertThat(analysis.durationMs).isEqualTo(8_000)
        assertThat(analysis.grid).isNull()
    }

    @Test
    fun `the result does not depend on the block size`() {
        val audio = SyntheticAudio.diatonicProgression(MusicalKey.C_MAJOR, chordMs = 1_000.0)
        val results = listOf(1, 333, 4_096, 100_000).map { block ->
            val pipeline = DjAnalysisPipeline(
                modelFactory = FakeModelFactory { null },
                fallbackGrid = CountingSinks<DjBeatGrid?>(44_100) { null }.asStage(),
                resampler = UnusedResampler,
                telemetry = RecordingTelemetry(),
                clock = SteppingClock(),
                blockFrames = block,
            )
            run(pipeline, ArrayPcmSource(audio, 44_100))
        }
        for (r in results.drop(1)) {
            assertThat(r.waveform).isEqualTo(results[0].waveform)
            assertThat(r.key).isEqualTo(results[0].key)
        }
    }

    @Test
    fun `phase refinement moves the grid onto the onset envelope fed in the same decode`() {
        val rig = Rig(model = { FakeModel() }, withOnset = true)
        val analysis = run(rig.build(), stereoSource(1.0))

        assertThat(analysis.grid).isEqualTo(refinedGridValue)
        assertThat(rig.refined.single().first).isEqualTo(modelGridValue)
        assertThat(rig.refined.single().second.frameCount).isEqualTo(100)
    }

    @Test
    fun `without an onset sink the grid is not refined`() {
        val rig = Rig(model = { FakeModel() }, withOnset = false)
        val analysis = run(rig.build(), stereoSource(1.0))
        assertThat(analysis.grid).isEqualTo(modelGridValue)
        assertThat(rig.refined).isEmpty()
    }

    // Progress

    @Test
    fun `progress rises monotonically through decode and inference and ends at 1`() {
        val rig = Rig(model = { FakeModel() }, melFrames = 10_000)
        val progress = ArrayList<Float>()
        run(rig.build(blockFrames = 500), stereoSource(3.0), progress)

        assertThat(progress.size).isGreaterThan(20)
        assertThat(progress.first()).isAtLeast(0f)
        assertThat(progress.last()).isEqualTo(1f)
        for (i in 1 until progress.size) assertWithMessage("step $i").that(progress[i]).isGreaterThan(progress[i - 1])
        assertThat(progress.count { it == 1f }).isEqualTo(1)
    }

    @Test
    fun `progress without a known length or a model still ends at 1`() {
        val rig = Rig(model = { null })
        val progress = ArrayList<Float>()
        run(rig.build(), stereoSource(1.0, lengthKnown = false), progress)

        assertThat(progress.last()).isEqualTo(1f)
        for (i in 1 until progress.size) assertThat(progress[i]).isGreaterThan(progress[i - 1])
    }

    // Cancellation and failures

    @Test
    fun `cancellation stops between blocks and reports nothing`() {
        val model = FakeModel()
        val rig = Rig(model = { model })
        val source = stereoSource(2.0)
        var checks = 0
        val thrown = failureOf {
            rig.build(blockFrames = 1_000).run(source, TEST_AUDIO_ID, {}) { if (++checks > 5) throw CancellationException("stop") }
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(source.reads).isAtMost(5)
        assertThat(rig.waveform.blocks).isAtMost(5)
        assertThat(rig.waveform.finished).isEqualTo(0)
        assertThat(model.calls).isEqualTo(0)
        assertThat(model.closed).isEqualTo(1)
        assertThat(rig.telemetry.analysed).isEmpty()
        assertThat(rig.telemetry.failed).isEmpty()
    }

    @Test
    fun `cancellation during inference is not taken for a model failure`() {
        val model = FakeModel()
        val rig = Rig(model = { model }, melFrames = 10_000)
        val thrown = failureOf {
            rig.build().run(stereoSource(1.0), TEST_AUDIO_ID, {}) { if (model.calls > 0) throw CancellationException("stop") }
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(model.calls).isEqualTo(1)
        assertThat(rig.fallback.finished).isEqualTo(0)
        assertThat(rig.telemetry.analysed).isEmpty()
        assertThat(rig.telemetry.failed).isEmpty()
    }

    @Test
    fun `a failing sink is reported with its stage and thrown`() {
        val failure = IllegalArgumentException("bad block")
        val rig = Rig(model = { FakeModel() })
        rig.chroma.acceptAction = { throw failure }
        val thrown = failureOf { rig.build().run(stereoSource(1.0), TEST_AUDIO_ID, {}, {}) }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(rig.telemetry.failed).containsExactly(AnalysisStage.KEY to failure)
        assertThat(rig.telemetry.analysed).isEmpty()
    }

    // Timings

    @Test
    fun `timings cover every stage that ran with the model`() {
        val rig = Rig(model = { FakeModel() })
        val analysis = run(rig.build(), stereoSource(1.0))

        val (id, timings, provenance) = rig.telemetry.analysed.single()
        assertThat(id).isEqualTo(TEST_AUDIO_ID)
        assertThat(provenance).isEqualTo(analysis.provenance)
        assertThat(timings.stageMs.keys).containsExactly(
            AnalysisStage.DECODE, AnalysisStage.WAVEFORM, AnalysisStage.MEL, AnalysisStage.INFERENCE, AnalysisStage.GRID, AnalysisStage.KEY,
        )
        assertThat(timings.stageMs.values.all { it >= 1 }).isTrue()
    }

    @Test
    fun `timings without a model leave out the mel and the inference`() {
        val rig = Rig(model = { null })
        run(rig.build(), stereoSource(1.0))

        val timings = rig.telemetry.analysed.single().second
        assertThat(timings.stageMs.keys).containsExactly(AnalysisStage.DECODE, AnalysisStage.WAVEFORM, AnalysisStage.GRID, AnalysisStage.KEY)
        assertThat(timings.stageMs.values.all { it >= 1 }).isTrue()
    }

    // End to end

    @Test
    fun `golden click chunks through the whole pipeline give a grid within 1 BPM of the clicks`() {
        for ((name, bpm) in listOf("click_090" to 90.0, "click_128" to 128.0)) {
            val grid = goldenGrid(name)
            assertWithMessage("$name bpm ${grid.segments}").that(abs(grid.segments.first().bpm - bpm)).isAtMost(1.0)
        }
    }

    /**
     * 174 BPM sits inside the default prior's range (70..180) but its octave preference (centred on 120 BPM) rates 87
     * above 174 and the golden downbeats (nearly every click) cannot overrule it, so the constant fit of D1.10 returns
     * half the tempo. The pipeline passes the tempo through intact up to that octave; the prior is D1.19/D1.20's to tune.
     */
    @Test
    fun `golden 174 BPM clicks give the tempo up to the octave the prior chooses`() {
        val bpm = goldenGrid("click_174").segments.first().bpm
        assertThat(minOf(abs(bpm - 174.0), abs(bpm - 87.0))).isAtMost(1.0)
    }

    /** The grid the whole pipeline finds for golden case [name], with a fake model answering the golden chunks. */
    private fun goldenGrid(name: String): DjBeatGrid {
        val case = BeatThisCase.load(name)
        val model = FakeModel { index, chunk ->
            check(chunk.frameCount == case.chunkLengths[index]) { "$name chunk $index has ${chunk.frameCount} frames" }
            case.chunks[index]
        }
        val telemetry = RecordingTelemetry()
        val pipeline = DjAnalysisPipeline(
            modelFactory = FakeModelFactory { model },
            fallbackGrid = CountingSinks<DjBeatGrid?>(22_050) { error("the fallback must not be used") }.asStage(),
            resampler = UnusedResampler,
            telemetry = telemetry,
            clock = SteppingClock(),
            waveform = StageSinks { BandWaveformSink(it) },
            chroma = StageSinks { HpcpSink(it) },
        )
        val analysis = run(pipeline, ArrayPcmSource(case.audio(), BeatThisCase.SAMPLE_RATE))

        assertWithMessage("$name chunks").that(model.calls).isEqualTo(case.chunks.size)
        assertWithMessage("$name source").that(analysis.provenance.beatSource).isEqualTo(BeatSource.MODEL)
        assertWithMessage("$name failures").that(telemetry.failed).isEmpty()
        assertWithMessage("$name grid").that(analysis.grid).isNotNull()
        return analysis.grid!!
    }

    @Test
    fun `golden clicks decoded at 44_1 kHz reach the model through the polyphase resampler`() {
        val case = BeatThisCase.load("click_128")
        val audio22k = case.audio()
        val up = PolyphaseResampler(22_050, 44_100, 1)
        val head = FloatArray(up.maxOutputFrames(audio22k.size))
        val n = up.process(audio22k, audio22k.size, head)
        val tail = FloatArray(up.maxOutputFrames(0))
        val audio44k = head.copyOf(n) + tail.copyOf(up.flush(tail))
        val model = FakeModel { index, chunk ->
            check(chunk.frameCount == case.chunkLengths[index]) { "chunk $index has ${chunk.frameCount} frames" }
            case.chunks[index]
        }
        val telemetry = RecordingTelemetry()
        val pipeline = DjAnalysisPipeline(
            modelFactory = FakeModelFactory { model },
            fallbackGrid = CountingSinks<DjBeatGrid?>(44_100) { error("the fallback must not be used") }.asStage(),
            telemetry = telemetry,
            clock = SteppingClock(),
        )
        val analysis = run(pipeline, ArrayPcmSource(audio44k, 44_100))

        assertThat(model.calls).isEqualTo(case.chunks.size)
        assertThat(telemetry.failed).isEmpty()
        assertThat(abs(analysis.grid!!.segments.first().bpm - 128.0)).isAtMost(1.0)
        assertThat(analysis.waveform.columnCount).isEqualTo(BandWaveformSink(44_100).create().apply { accept(audio44k, 0, audio44k.size) }.finish().columnCount)
    }

    @Test
    fun `golden silence gives no grid from the model`() {
        val case = BeatThisCase.load("silence")
        val model = FakeModel { index, _ -> case.chunks[index] }
        val pipeline = DjAnalysisPipeline(
            modelFactory = FakeModelFactory { model },
            fallbackGrid = CountingSinks<DjBeatGrid?>(22_050) { fallbackGridValue }.asStage(),
            resampler = UnusedResampler,
            telemetry = RecordingTelemetry(),
            clock = SteppingClock(),
            waveform = StageSinks { BandWaveformSink(it) },
            chroma = StageSinks { HpcpSink(it) },
        )
        val analysis = run(pipeline, ArrayPcmSource(case.audio(), BeatThisCase.SAMPLE_RATE))

        assertThat(analysis.grid).isNull()
        assertThat(analysis.key).isNull()
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.MODEL)
    }
}
