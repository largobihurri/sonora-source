package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.analysis.AnalysisEngine
import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.analysis.EngineResult
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.AnalysisException
import org.junit.Assert.assertThrows
import org.junit.Test

class QmDspGridSinkTest {

    // EngineResult -> DjBeatGrid

    @Test
    fun `a result becomes one segment at the first beat with its tempo and bar position`() {
        val grid = QmDspGrid.toDjBeatGrid(result(bpm = 128.0, firstBeatFrame = 22_050.0, firstBeatInBar = 3), 44_100)

        assertThat(grid.segments).containsExactly(TempoSegment(startMs = 500.0, bpm = 128.0, beatInBarAtStart = 3))
    }

    @Test
    fun `the first beat time uses the analysed rate`() {
        val grid = QmDspGrid.toDjBeatGrid(result(bpm = 120.0, firstBeatFrame = 12_000.0, firstBeatInBar = 1), 48_000)

        assertThat(grid.segments.single().startMs).isEqualTo(250.0)
    }

    @Test
    fun `a fractional tempo is kept as it is`() {
        val grid = QmDspGrid.toDjBeatGrid(result(bpm = 123.456, firstBeatFrame = 0.0, firstBeatInBar = 1), 44_100)

        assertThat(grid.segments.single().bpm).isEqualTo(123.456)
    }

    @Test
    fun `a first beat just before frame 0 moves on one beat and one bar position`() {
        // 120 BPM = 500 ms per beat; -10 ms at 1000 Hz is frame -10.
        val grid = QmDspGrid.toDjBeatGrid(result(bpm = 120.0, firstBeatFrame = -10.0, firstBeatInBar = 4), 1_000)

        assertThat(grid.segments).containsExactly(TempoSegment(startMs = 490.0, bpm = 120.0, beatInBarAtStart = 1))
    }

    @Test
    fun `values that cannot be a grid are an engine failure`() {
        val bad = listOf(
            result(bpm = 0.0),
            result(bpm = -120.0),
            result(bpm = Double.NaN),
            result(firstBeatFrame = Double.POSITIVE_INFINITY),
            result(firstBeatInBar = 0),
            result(firstBeatInBar = 5),
        )
        for (r in bad) {
            assertThrows(AnalysisException.EngineFailure::class.java) { QmDspGrid.toDjBeatGrid(r, 44_100) }
        }
    }

    // The sink

    @Test
    fun `runs at the source rate when the engine supports it, else at 44_1 kHz`() {
        val sink = QmDspGridSink(FakeEngines())

        assertThat(sink.forSourceRate(48_000).sampleRateHz).isEqualTo(48_000)
        assertThat(sink.forSourceRate(8_000).sampleRateHz).isEqualTo(8_000)
        assertThat(sink.forSourceRate(192_000).sampleRateHz).isEqualTo(192_000)
        assertThat(sink.forSourceRate(7_999).sampleRateHz).isEqualTo(44_100)
        assertThat(sink.forSourceRate(384_000).sampleRateHz).isEqualTo(44_100)
    }

    @Test
    fun `feeds the engine every sample from index 0 whatever the offset, and maps its result`() {
        val engines = FakeEngines(result = result(bpm = 100.0, firstBeatFrame = 4_800.0, firstBeatInBar = 2))
        val sink = QmDspGridSink(engines).forSourceRate(48_000).create()
        val block = FloatArray(10) { it.toFloat() }

        sink.accept(block, 0, 4)
        sink.accept(block, 6, 3)
        val grid = sink.finish()

        val engine = engines.created.single()
        assertThat(engine.rate).isEqualTo(48_000)
        assertThat(engine.received).containsExactly(0f, 1f, 2f, 3f, 6f, 7f, 8f).inOrder()
        assertThat(grid).isEqualTo(DjBeatGrid(listOf(TempoSegment(100.0, 100.0, 2))))
    }

    @Test
    fun `audio too short or without beats gives no grid`() {
        val short = QmDspGridSink(FakeEngines(finishFailure = AnalysisException.TooShort(1_000, 5_000))).forSourceRate(44_100).create()
        val beatless = QmDspGridSink(FakeEngines(finishFailure = AnalysisException.NoBeatsDetected())).forSourceRate(44_100).create()

        assertThat(short.finish()).isNull()
        assertThat(beatless.finish()).isNull()
    }

    @Test
    fun `an engine failure on finish is thrown`() {
        val sink = QmDspGridSink(FakeEngines(finishFailure = AnalysisException.EngineFailure("boom"))).forSourceRate(44_100).create()

        assertThrows(AnalysisException.EngineFailure::class.java) { sink.finish() }
    }

    @Test
    fun `an engine that cannot be created fails only when the grid is asked for`() {
        val sink = QmDspGridSink(FakeEngines(createFailure = AnalysisException.EngineFailure("no native library")))
            .forSourceRate(44_100).create()

        sink.accept(FloatArray(16), 0, 16)
        (sink as AutoCloseable).close()

        assertThrows(AnalysisException.EngineFailure::class.java) { sink.finish() }
    }

    @Test
    fun `a failure while feeding stops the feeding and is thrown on finish`() {
        val engines = FakeEngines(processFailure = AnalysisException.EngineFailure("out of memory"))
        val sink = QmDspGridSink(engines).forSourceRate(44_100).create()

        sink.accept(FloatArray(4), 0, 4)
        sink.accept(FloatArray(4), 0, 4)

        assertThat(engines.created.single().processCalls).isEqualTo(1)
        assertThrows(AnalysisException.EngineFailure::class.java) { sink.finish() }
    }

    @Test
    fun `the sink is closeable and releases its engine once, finished or not`() {
        val engines = FakeEngines()
        val unfinished = QmDspGridSink(engines).forSourceRate(44_100).create()
        val finished = QmDspGridSink(engines).forSourceRate(44_100).create()

        assertThat(unfinished).isInstanceOf(AutoCloseable::class.java)
        (unfinished as AutoCloseable).close()
        unfinished.close()
        finished.finish()
        (finished as AutoCloseable).close()

        assertThat(engines.created.map { it.closeCalls }).containsExactly(1, 1)
    }

    private fun MonoSink<*>.close() = (this as AutoCloseable).close()

    private fun result(
        bpm: Double = 120.0,
        firstBeatFrame: Double = 0.0,
        firstBeatInBar: Int = 1,
    ) = EngineResult(bpm, firstBeatFrame, firstBeatInBar, keyIndex = 0, totalFrames = 441_000, detectedBeats = 100)

    private class FakeEngines(
        private val result: EngineResult = EngineResult(120.0, 0.0, 1, 0, 441_000, 100),
        private val createFailure: AnalysisException? = null,
        private val processFailure: AnalysisException? = null,
        private val finishFailure: AnalysisException? = null,
    ) : AnalysisEngineFactory {
        val created = ArrayList<FakeEngine>()

        override fun create(sampleRateHz: Int): AnalysisEngine {
            createFailure?.let { throw it }
            return FakeEngine(sampleRateHz, result, processFailure, finishFailure).also { created += it }
        }
    }

    private class FakeEngine(
        val rate: Int,
        private val result: EngineResult,
        private val processFailure: AnalysisException?,
        private val finishFailure: AnalysisException?,
    ) : AnalysisEngine {
        val received = ArrayList<Float>()
        var processCalls = 0
        var closeCalls = 0

        override fun process(samples: FloatArray, count: Int) {
            processCalls++
            processFailure?.let { throw it }
            for (i in 0 until count) received += samples[i]
        }

        override fun finish(): EngineResult {
            finishFailure?.let { throw it }
            return result
        }

        override fun close() {
            closeCalls++
        }
    }
}
