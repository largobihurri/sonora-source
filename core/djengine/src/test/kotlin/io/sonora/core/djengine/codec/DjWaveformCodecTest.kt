package io.sonora.core.djengine.codec

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BandWaveform
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class DjWaveformCodecTest {

    /**
     * Planes shaped like a four-on-the-floor track at 128 BPM and 150 columns/s: a kick every 70 columns (bass
     * decaying), an off-beat hat (highs), a held pad (mids) that changes level every 16 bars. Quantised like the real
     * sink's output, so the repetition is exact as in a loop-based track.
     */
    private fun trackLike(columns: Int): BandWaveform {
        val low = ByteArray(columns)
        val mid = ByteArray(columns)
        val high = ByteArray(columns)
        val peak = ByteArray(columns)
        val beat = 70
        for (i in 0 until columns) {
            val inBeat = i % beat
            val section = (i / (beat * 64)) % 5
            low[i] = (255 - inBeat * 3).coerceAtLeast(40).toByte()
            mid[i] = (110 + 20 * section + if (inBeat < 10) 30 else 0).toByte()
            high[i] = (if (inBeat in 33..40) 200 else 20 + (inBeat and 3)).toByte()
            peak[i] = maxOf(low[i].toInt() and 0xFF, mid[i].toInt() and 0xFF, high[i].toInt() and 0xFF).toByte()
        }
        return BandWaveform(low, mid, high, peak, version = 1)
    }

    private fun roundTrip(waveform: BandWaveform): BandWaveform {
        val decoded = DjAnalysisCodec.decodeWaveform(DjAnalysisCodec.encodeWaveform(waveform))
        assertThat(decoded).isEqualTo(waveform)
        assertThat(decoded.version).isEqualTo(waveform.version)
        return decoded
    }

    @Test
    fun `a waveform round trips plane by plane`() {
        val waveform = trackLike(5000)

        val decoded = roundTrip(waveform)

        assertThat(decoded.low()).isEqualTo(waveform.low())
        assertThat(decoded.mid()).isEqualTo(waveform.mid())
        assertThat(decoded.high()).isEqualTo(waveform.high())
        assertThat(decoded.peak()).isEqualTo(waveform.peak())
    }

    @Test
    fun `an empty waveform and a one column waveform round trip`() {
        roundTrip(BandWaveform(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), version = 1))
        roundTrip(BandWaveform(byteArrayOf(-1), byteArrayOf(0), byteArrayOf(127), byteArrayOf(-128), version = 1))
    }

    @Test
    fun `the full byte range and large versions round trip`() {
        val all = ByteArray(256) { it.toByte() }
        roundTrip(BandWaveform(all, all.reversedArray(), all, all.reversedArray(), version = Int.MAX_VALUE))
    }

    @Test
    fun `six minutes of columns compresses far below the raw size`() {
        val columns = 6 * 60 * BandWaveform.COLUMNS_PER_SECOND
        assertThat(columns).isEqualTo(54_000)
        val waveform = trackLike(columns)

        val encoded = DjAnalysisCodec.encodeWaveform(waveform)

        val raw = 4 * columns
        assertThat(encoded.size).isLessThan(raw / 10)
        assertThat(DjAnalysisCodec.decodeWaveform(encoded)).isEqualTo(waveform)
    }

    @Test
    fun `incompressible planes cost almost nothing over the raw size`() {
        val random = Random(42)
        val columns = 54_000
        fun plane() = ByteArray(columns).also { random.nextBytes(it) }
        val waveform = BandWaveform(plane(), plane(), plane(), plane(), version = 3)

        val encoded = DjAnalysisCodec.encodeWaveform(waveform)

        assertThat(encoded.size).isLessThan(4 * columns + 4 * columns / 50)
        assertThat(DjAnalysisCodec.decodeWaveform(encoded)).isEqualTo(waveform)
    }

    @Test
    fun `a bit flipped inside the deflate stream is reported as corrupt even with a valid checksum`() {
        val encoded = DjAnalysisCodec.encodeWaveform(trackLike(20_000))
        val streamStart = 4 + 2 + 1 + 12 // header + algorithm version + column count + compressed length
        val streamEnd = encoded.size - 4

        for (step in 1..9) {
            val position = streamStart + (streamEnd - streamStart) * step / 10
            val damaged = encoded.copyOf()
            damaged[position] = (damaged[position].toInt() xor 0x10).toByte()

            assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(RecordBytes.refreshCrc(damaged)) }
            // And without refreshing, the record checksum catches it first.
            assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(damaged) }
        }
    }

    @Test
    fun `a column count that disagrees with the stream is corrupt`() {
        val waveform = trackLike(1000)
        val stream = deflatePlanes(waveform)

        for (declared in longArrayOf(999, 1001, 0, 100_000)) {
            val bytes = RecordBytes.record(
                RecordBytes.KIND_WAVEFORM,
                RecordBytes.payload { u32(1).u32(declared).u32(stream.size.toLong()).raw(stream) },
            )
            assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(bytes) }
        }
    }

    @Test
    fun `a compressed length that disagrees with the record is corrupt`() {
        val stream = deflatePlanes(trackLike(1000))

        for (declared in longArrayOf(stream.size - 1L, stream.size + 1L, 0, 0xFFFFFFFFL)) {
            val bytes = RecordBytes.record(
                RecordBytes.KIND_WAVEFORM,
                RecordBytes.payload { u32(1).u32(1000).u32(declared).raw(stream) },
            )
            assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(bytes) }
        }
    }

    @Test
    fun `an absurd column count is rejected without allocating it`() {
        val stream = deflatePlanes(trackLike(10))
        val bytes = RecordBytes.record(
            RecordBytes.KIND_WAVEFORM,
            RecordBytes.payload { u32(1).u32(0xFFFFFFFFL).u32(stream.size.toLong()).raw(stream) },
        )

        assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(bytes) }
    }

    @Test
    fun `a stream with data after the planes is corrupt`() {
        val waveform = trackLike(1000)
        val longer = trackLike(1001)
        val stream = deflatePlanes(longer)

        val bytes = RecordBytes.record(
            RecordBytes.KIND_WAVEFORM,
            RecordBytes.payload { u32(1).u32(1000).u32(stream.size.toLong()).raw(stream) },
        )

        assertThat(waveform.columnCount).isEqualTo(1000)
        assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(bytes) }
    }

    @Test
    fun `an algorithm version of zero is corrupt`() {
        val stream = deflatePlanes(trackLike(100))
        val bytes = RecordBytes.record(
            RecordBytes.KIND_WAVEFORM,
            RecordBytes.payload { u32(0).u32(100).u32(stream.size.toLong()).raw(stream) },
        )

        assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeWaveform(bytes) }
    }

    @Test
    fun `a valid hand built record decodes`() {
        val waveform = trackLike(100)
        val stream = deflatePlanes(waveform)
        val bytes = RecordBytes.record(
            RecordBytes.KIND_WAVEFORM,
            RecordBytes.payload { u32(7).u32(100).u32(stream.size.toLong()).raw(stream) },
        )

        val decoded = DjAnalysisCodec.decodeWaveform(bytes)

        assertThat(decoded.version).isEqualTo(7)
        assertThat(decoded.low()).isEqualTo(waveform.low())
        assertThat(decoded.peak()).isEqualTo(waveform.peak())
    }

    /** The planes in file order (low, mid, high, peak), zlib-compressed. */
    private fun deflatePlanes(waveform: BandWaveform): ByteArray {
        val raw = waveform.low() + waveform.mid() + waveform.high() + waveform.peak()
        val deflater = java.util.zip.Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
