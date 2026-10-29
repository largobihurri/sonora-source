package io.sonora.core.djengine.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Assert.assertThrows
import org.junit.Test

class DjBackupCodecTest {

    private val audioId = AudioId.of("ap1", "0123456789abcdef".repeat(4))
    private val constantGrid = DjBeatGrid(listOf(TempoSegment(250.5, 120.0, 1)))
    private val modelProvenance = AnalysisProvenance(
        analyzerVersion = 1,
        beatSource = BeatSource.MODEL,
        modelId = "beatthis-small0",
        accelerator = Accelerator.CPU,
        analysedAtEpochMs = 1_760_000_000_000,
    )

    private fun record(
        grid: DjBeatGrid? = constantGrid,
        key: KeyEstimate? = KeyEstimate(MusicalKey.C_MINOR, 0.75, "edma"),
        provenance: AnalysisProvenance = modelProvenance,
        audioId: AudioId = this.audioId,
        sampleRate: Int = 44_100,
        totalFrames: Long = 13_230_000,
    ) = DjBackupRecord(audioId, sampleRate, totalFrames, grid, key, provenance)

    private fun roundTrip(record: DjBackupRecord): DjBackupRecord {
        val decoded = DjAnalysisCodec.decodeBackup(DjAnalysisCodec.encodeBackup(record))
        assertThat(decoded).isEqualTo(record)
        return decoded
    }

    @Test
    fun `a full record round trips`() {
        val decoded = roundTrip(record())

        assertThat(decoded.audioId).isEqualTo(audioId)
        assertThat(decoded.sampleRate).isEqualTo(44_100)
        assertThat(decoded.totalFrames).isEqualTo(13_230_000L)
        assertThat(decoded.key).isEqualTo(KeyEstimate(MusicalKey.C_MINOR, 0.75, "edma"))
    }

    @Test
    fun `beatless audio and a missing key round trip`() {
        roundTrip(record(grid = null))
        roundTrip(record(key = null))
        roundTrip(record(grid = null, key = null))
    }

    @Test
    fun `a multi segment grid and its doubles survive bit for bit`() {
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(0.1 + 0.2, 127.99999999999999, 2),
                TempoSegment(0.1 + 0.2 + 8 * (60_000.0 / 127.99999999999999), 90.0, 4),
            ),
        )

        val decoded = roundTrip(record(grid = grid))

        for ((a, e) in decoded.grid!!.segments.zip(grid.segments)) {
            assertThat(java.lang.Double.doubleToRawLongBits(a.startMs)).isEqualTo(java.lang.Double.doubleToRawLongBits(e.startMs))
            assertThat(java.lang.Double.doubleToRawLongBits(a.bpm)).isEqualTo(java.lang.Double.doubleToRawLongBits(e.bpm))
        }
    }

    @Test
    fun `every key and both strength limits round trip`() {
        for (key in MusicalKey.entries) roundTrip(record(key = KeyEstimate(key, 0.0, "m")))
        roundTrip(record(key = KeyEstimate(MusicalKey.A_MAJOR, 1.0, "m")))
        roundTrip(record(key = KeyEstimate(MusicalKey.A_MAJOR, 0.1 + 0.2, "edma")))
    }

    @Test
    fun `every beat source and accelerator combination round trips`() {
        roundTrip(record(provenance = AnalysisProvenance(1, BeatSource.QM_DSP, null, null, 5)))
        for (accelerator in listOf(null) + Accelerator.entries) {
            roundTrip(record(provenance = AnalysisProvenance(3, BeatSource.MODEL, "m", accelerator, 5)))
        }
        roundTrip(record(provenance = AnalysisProvenance(2, BeatSource.QM_DSP, null, Accelerator.NPU, 5)))
    }

    @Test
    fun `extreme numbers and unicode text round trip`() {
        roundTrip(
            record(
                audioId = AudioId.of("zz9", "f".repeat(64)),
                sampleRate = Int.MAX_VALUE,
                totalFrames = Long.MAX_VALUE,
                key = KeyEstimate(MusicalKey.D_FLAT_MAJOR, 0.5, "edma ñandú 🎵"),
                provenance = AnalysisProvenance(Int.MAX_VALUE, BeatSource.MODEL, "método-あ", null, Long.MIN_VALUE),
            ),
        )
        roundTrip(record(totalFrames = 0, provenance = AnalysisProvenance(1, BeatSource.QM_DSP, null, null, Long.MAX_VALUE)))
    }

    @Test
    fun `a backup does not carry a waveform and stays small`() {
        val encoded = DjAnalysisCodec.encodeBackup(record())

        assertThat(encoded.size).isLessThan(200)
    }

    @Test
    fun `FROZEN golden bytes of a v1 backup record`() {
        // magic SDJ1 | version 1 | kind 3 | audio id (u16 length + UTF-8) | sample rate u32 | total frames i64 |
        // flags 0b11 (grid, key) | grid (count u32, segments) | key (index u8, strength f64, method string) |
        // provenance (analyzer version u32, beat source u8 0=MODEL, flags 0b11 = model id + accelerator,
        // model id string, accelerator u8 0=CPU, analysed-at i64) | crc32. Never edit this literal to make a test
        // pass: a change here means the stored format changed.
        val golden = "53444a3100010300446170313a303132333435363738396162636465663031323334353637383961626364656630313233" +
            "343536373839616263646566303132333435363738396162636465660000ac440000000000c9dfb00300000001406f500000" +
            "000000405e000000000000010d3fe8000000000000000465646d61000000010003000f62656174746869732d736d616c6c3000" +
            "00000199c82cc00009be1b22"

        assertThat(RecordBytes.hexOf(DjAnalysisCodec.encodeBackup(record()))).isEqualTo(golden)
        assertThat(DjAnalysisCodec.decodeBackup(RecordBytes.hex(golden))).isEqualTo(record())
    }

    // Hand built records with a valid checksum but invalid content: the decoder must refuse them.

    private fun backupPayload(
        id: String = audioId.value,
        sampleRate: Long = 44_100,
        frames: Long = 1_000,
        flags: Int = 0,
        keyIndex: Int = 13,
        strength: Double = 0.5,
        method: String = "edma",
        beatSource: Int = 0,
        provenanceFlags: Int = 0b01,
        modelId: String? = "m",
        accelerator: Int? = null,
        analyzerVersion: Long = 1,
        rawKeyBlock: Boolean = false,
    ): ByteArray = RecordBytes.payload {
        string(id).u32(sampleRate).i64(frames).u8(flags)
        if (flags and 1 != 0) u32(1).segment(0.0, 120.0, 1)
        if (flags and 2 != 0) u8(keyIndex).f64(strength).string(method)
        u32(analyzerVersion).u8(beatSource).u8(provenanceFlags)
        if (provenanceFlags and 1 != 0) string(modelId!!)
        if (provenanceFlags and 2 != 0) u8(accelerator!!)
        i64(1)
    }

    private fun decode(payload: ByteArray) = DjAnalysisCodec.decodeBackup(RecordBytes.record(RecordBytes.KIND_BACKUP, payload))

    @Test
    fun `the hand built baseline decodes`() {
        val decoded = decode(backupPayload(flags = 0b11, provenanceFlags = 0b11, accelerator = 2))

        assertThat(decoded.key!!.key).isEqualTo(MusicalKey.C_MINOR)
        assertThat(decoded.provenance.accelerator).isEqualTo(Accelerator.NPU)
    }

    @Test
    fun `invalid fields are corrupt`() {
        val invalid = mapOf(
            "malformed audio id" to backupPayload(id = "not an id"),
            "zero sample rate" to backupPayload(sampleRate = 0),
            "sample rate above Int" to backupPayload(sampleRate = 0x8000_0000L),
            "negative frames" to backupPayload(frames = -1),
            "reserved flag bit" to backupPayload(flags = 0b100),
            "key index 0" to backupPayload(flags = 0b10, keyIndex = 0),
            "key index 25" to backupPayload(flags = 0b10, keyIndex = 25),
            "strength above 1" to backupPayload(flags = 0b10, strength = 1.5),
            "NaN strength" to backupPayload(flags = 0b10, strength = Double.NaN),
            "blank method" to backupPayload(flags = 0b10, method = "  "),
            "unknown beat source" to backupPayload(beatSource = 9),
            "model id with the qm-dsp source" to backupPayload(beatSource = 1),
            "model source without model id" to backupPayload(provenanceFlags = 0, modelId = null),
            "unknown accelerator" to backupPayload(provenanceFlags = 0b11, accelerator = 3),
            "reserved provenance flag" to backupPayload(provenanceFlags = 0b101),
            "analyzer version 0" to backupPayload(analyzerVersion = 0),
        )

        for ((name, payload) in invalid) {
            val error = runCatching { decode(payload) }.exceptionOrNull()
            assertWithMessage(name).that(error).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `invalid UTF-8 in a string is corrupt`() {
        val payload = RecordBytes.payload {
            u16(4).raw(byteArrayOf('a'.code.toByte(), 'p'.code.toByte(), 0xC3.toByte(), 0x28))
            u32(44_100).i64(1).u8(0)
            u32(1).u8(1).u8(0).i64(1)
        }

        assertThrows(DjCodecException.Corrupt::class.java) { decode(payload) }
    }

    @Test
    fun `an invalid grid inside a backup is corrupt`() {
        val payload = RecordBytes.payload {
            string(audioId.value).u32(44_100).i64(1).u8(1)
            u32(1).segment(0.0, 0.0, 1)
            u32(1).u8(1).u8(0).i64(1)
        }

        assertThrows(DjCodecException.Corrupt::class.java) { decode(payload) }
    }

    @Test
    fun `a string length that runs past the record is corrupt`() {
        val payload = RecordBytes.payload { u16(60_000).raw(ByteArray(10)) }

        assertThrows(DjCodecException.Corrupt::class.java) { decode(payload) }
    }
}
