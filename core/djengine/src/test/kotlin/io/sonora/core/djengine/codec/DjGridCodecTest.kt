package io.sonora.core.djengine.codec

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class DjGridCodecTest {

    private fun bits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

    private fun assertBitExact(actual: DjBeatGrid, expected: DjBeatGrid) {
        assertThat(actual.segments).hasSize(expected.segments.size)
        for ((a, e) in actual.segments.zip(expected.segments)) {
            assertThat(bits(a.startMs)).isEqualTo(bits(e.startMs))
            assertThat(bits(a.bpm)).isEqualTo(bits(e.bpm))
            assertThat(a.beatInBarAtStart).isEqualTo(e.beatInBarAtStart)
        }
    }

    private fun roundTrip(grid: DjBeatGrid): DjBeatGrid {
        val decoded = DjAnalysisCodec.decodeGrid(DjAnalysisCodec.encodeGrid(grid))
        assertBitExact(decoded, grid)
        assertThat(decoded).isEqualTo(grid)
        return decoded
    }

    @Test
    fun `a constant grid round trips with bit exact doubles`() {
        roundTrip(DjBeatGrid(listOf(TempoSegment(1234.5678901234567, 127.99999999999999, 3))))
        roundTrip(DjBeatGrid(listOf(TempoSegment(0.1 + 0.2, 1.0 / 3.0 * 360.0, 1))))
    }

    @Test
    fun `a multi segment grid round trips`() {
        // 250.5 ms at 120 BPM (500 ms beats): 4250.5 is 8 beats later; 128 BPM beats are 468.75 ms: +16 beats = 11750.5.
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(250.5, 120.0, 1),
                TempoSegment(4250.5, 128.0, 3),
                TempoSegment(11750.5, 90.5, 4),
            ),
        )

        val decoded = roundTrip(grid)

        assertThat(decoded.segments.map { it.bpm }).containsExactly(120.0, 128.0, 90.5).inOrder()
    }

    @Test
    fun `a per beat grid of hundreds of segments round trips`() {
        val segments = ArrayList<TempoSegment>()
        var start = 100.0
        for (i in 0 until 768) {
            val bpm = 128.0 + 0.37 * Math.sin(i * 0.1)
            segments += TempoSegment(start, bpm, i % 4 + 1)
            start += 60_000.0 / bpm
        }

        roundTrip(DjBeatGrid(segments))
    }

    @Test
    fun `extreme values round trip bit for bit`() {
        roundTrip(DjBeatGrid(listOf(TempoSegment(Double.MAX_VALUE, Double.MIN_VALUE, 4))))
        roundTrip(DjBeatGrid(listOf(TempoSegment(Double.MIN_VALUE, Double.MAX_VALUE, 1))))
        roundTrip(DjBeatGrid(listOf(TempoSegment(0.0, 1e-300, 2))))
    }

    @Test
    fun `negative zero keeps its sign bit`() {
        val decoded = DjAnalysisCodec.decodeGrid(DjAnalysisCodec.encodeGrid(DjBeatGrid(listOf(TempoSegment(-0.0, 120.0, 1)))))

        assertThat(bits(decoded.segments.single().startMs)).isEqualTo(bits(-0.0))
    }

    @Test
    fun `the encoding is deterministic and has the documented size`() {
        val grid = DjBeatGrid(listOf(TempoSegment(250.5, 120.0, 1), TempoSegment(4250.5, 128.0, 3)))

        val first = DjAnalysisCodec.encodeGrid(grid)

        assertThat(first).isEqualTo(DjAnalysisCodec.encodeGrid(grid))
        // magic 4 + version 2 + kind 1 + count 4 + 2 * 17 + crc 4
        assertThat(first.size).isEqualTo(4 + 2 + 1 + 4 + 2 * 17 + 4)
    }

    @Test
    fun `FROZEN golden bytes of a v1 grid`() {
        val grid = DjBeatGrid(listOf(TempoSegment(250.5, 120.0, 1), TempoSegment(4250.5, 128.0, 3)))
        // magic SDJ1 | version 1 | kind 1 | count 2 | (250.5, 120.0, 1) | (4250.5, 128.0, 3) | crc32. Never edit this
        // literal to make a test pass: a change here means the stored format changed.
        val golden = "53444a3100010100000002406f500000000000405e0000000000000140b09a80000000004060000000000000038756b75b"

        assertThat(RecordBytes.hexOf(DjAnalysisCodec.encodeGrid(grid))).isEqualTo(golden)
        assertBitExact(DjAnalysisCodec.decodeGrid(RecordBytes.hex(golden)), grid)
    }
}
