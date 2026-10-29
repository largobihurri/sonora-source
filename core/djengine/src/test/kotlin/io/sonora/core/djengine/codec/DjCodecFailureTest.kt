package io.sonora.core.djengine.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Assert.assertThrows
import org.junit.Test

/** What every decoder does with records that are not valid v1 records of its kind. */
class DjCodecFailureTest {

    private val grid = DjBeatGrid(listOf(TempoSegment(250.5, 120.0, 1), TempoSegment(4250.5, 128.0, 3)))
    private val gridBytes = DjAnalysisCodec.encodeGrid(grid)
    private val waveform = BandWaveform(ByteArray(300) { it.toByte() }, ByteArray(300), ByteArray(300), ByteArray(300), version = 1)
    private val waveformBytes = DjAnalysisCodec.encodeWaveform(waveform)
    private val backupBytes = DjAnalysisCodec.encodeBackup(
        io.sonora.core.djengine.codec.DjBackupRecord(
            io.sonora.core.djmodel.AudioId.of("ap1", "ab".repeat(32)),
            48_000,
            10_000,
            grid,
            io.sonora.core.djmodel.KeyEstimate(io.sonora.core.domain.analysis.MusicalKey.E_MINOR, 0.5, "edma"),
            io.sonora.core.djmodel.AnalysisProvenance(1, io.sonora.core.djmodel.BeatSource.QM_DSP, null, null, 7),
        ),
    )

    private val decoders: Map<String, Pair<ByteArray, (ByteArray) -> Any>> = mapOf(
        "grid" to (gridBytes to { b: ByteArray -> DjAnalysisCodec.decodeGrid(b) }),
        "waveform" to (waveformBytes to { b: ByteArray -> DjAnalysisCodec.decodeWaveform(b) }),
        "backup" to (backupBytes to { b: ByteArray -> DjAnalysisCodec.decodeBackup(b) }),
    )

    private fun failure(decode: (ByteArray) -> Any, bytes: ByteArray): DjCodecException? =
        runCatching { decode(bytes) }.exceptionOrNull() as? DjCodecException

    @Test
    fun `every proper prefix is corrupt`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            for (length in bytes.indices) {
                val error = failure(decode, bytes.copyOf(length))
                assertWithMessage("$name truncated to $length").that(error).isInstanceOf(DjCodecException.Corrupt::class.java)
            }
        }
    }

    @Test
    fun `appended bytes are corrupt`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            assertWithMessage("$name + 1 byte").that(failure(decode, bytes + 0)).isInstanceOf(DjCodecException.Corrupt::class.java)
            // Even with a checksum that covers the extra byte.
            val padded = RecordBytes.withCrc(bytes.copyOf(bytes.size - 4) + 0)
            assertWithMessage("$name + 1 checksummed byte").that(failure(decode, padded)).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `a wrong magic is reported as bad magic`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            for (magic in listOf("SDJ2", "sdj1", "RIFF", "\u0000\u0000\u0000\u0000")) {
                val damaged = bytes.copyOf()
                magic.toByteArray(Charsets.ISO_8859_1).copyInto(damaged)
                assertWithMessage("$name with magic $magic").that(failure(decode, damaged)).isInstanceOf(DjCodecException.BadMagic::class.java)
            }
        }
    }

    @Test
    fun `short foreign data is bad magic and an empty array is corrupt`() {
        for ((name, pair) in decoders) {
            val decode = pair.second
            assertWithMessage(name).that(failure(decode, "hello".toByteArray())).isInstanceOf(DjCodecException.BadMagic::class.java)
            assertWithMessage(name).that(failure(decode, byteArrayOf(0x53))).isInstanceOf(DjCodecException.Corrupt::class.java)
            assertWithMessage(name).that(failure(decode, byteArrayOf(0x41))).isInstanceOf(DjCodecException.BadMagic::class.java)
            assertWithMessage(name).that(failure(decode, ByteArray(0))).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `a newer format version is unsupported and keeps its number`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            for (version in listOf(2, 3, 255, 256, 65_535)) {
                val newer = bytes.copyOf()
                newer[4] = (version shr 8).toByte()
                newer[5] = version.toByte()
                for (candidate in listOf(newer, RecordBytes.refreshCrc(newer))) {
                    val error = failure(decode, candidate)
                    assertWithMessage("$name v$version").that(error).isInstanceOf(DjCodecException.UnsupportedVersion::class.java)
                    assertThat((error as DjCodecException.UnsupportedVersion).version).isEqualTo(version)
                }
            }
        }
    }

    @Test
    fun `version zero was never written and is corrupt`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            val zero = bytes.copyOf().also { it[4] = 0; it[5] = 0 }
            assertWithMessage(name).that(failure(decode, RecordBytes.refreshCrc(zero))).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `a record of another kind is corrupt`() {
        for ((name, pair) in decoders) {
            val decode = pair.second
            for ((otherName, other) in decoders) {
                if (otherName == name) continue
                assertWithMessage("$name decoder given $otherName").that(failure(decode, other.first)).isInstanceOf(DjCodecException.Corrupt::class.java)
            }
            val unknownKind = pair.first.copyOf().also { it[6] = 9 }
            assertWithMessage("$name unknown kind").that(failure(decode, RecordBytes.refreshCrc(unknownKind))).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `every single bit flip is detected`() {
        for ((name, pair) in decoders) {
            val (bytes, decode) = pair
            for (byteIndex in bytes.indices) {
                for (bit in 0 until 8) {
                    val damaged = bytes.copyOf()
                    damaged[byteIndex] = (damaged[byteIndex].toInt() xor (1 shl bit)).toByte()
                    assertWithMessage("$name byte $byteIndex bit $bit").that(failure(decode, damaged)).isNotNull()
                }
            }
        }
    }

    @Test
    fun `a grid whose segment count disagrees with its length is corrupt`() {
        for (declared in longArrayOf(0, 1, 3, 1_000_000, 0xFFFFFFFFL)) {
            val bytes = RecordBytes.record(
                RecordBytes.KIND_GRID,
                RecordBytes.payload { u32(declared).segment(250.5, 120.0, 1).segment(4250.5, 128.0, 3) },
            )
            assertThrows(DjCodecException.Corrupt::class.java) { DjAnalysisCodec.decodeGrid(bytes) }
        }
    }

    @Test
    fun `a grid with invalid segments is corrupt`() {
        val invalid = mapOf(
            "NaN start" to RecordBytes.payload { u32(1).segment(Double.NaN, 120.0, 1) },
            "negative start" to RecordBytes.payload { u32(1).segment(-1.0, 120.0, 1) },
            "infinite bpm" to RecordBytes.payload { u32(1).segment(0.0, Double.POSITIVE_INFINITY, 1) },
            "zero bpm" to RecordBytes.payload { u32(1).segment(0.0, 0.0, 1) },
            "negative bpm" to RecordBytes.payload { u32(1).segment(0.0, -120.0, 1) },
            "beat in bar 0" to RecordBytes.payload { u32(1).segment(0.0, 120.0, 0) },
            "beat in bar 5" to RecordBytes.payload { u32(1).segment(0.0, 120.0, 5) },
            "starts not increasing" to RecordBytes.payload { u32(2).segment(4250.5, 120.0, 1).segment(250.5, 128.0, 1) },
            "second start off the beat" to RecordBytes.payload { u32(2).segment(250.5, 120.0, 1).segment(4300.0, 128.0, 1) },
            "no segments" to RecordBytes.payload { u32(0) },
        )

        for ((name, payload) in invalid) {
            val error = runCatching { DjAnalysisCodec.decodeGrid(RecordBytes.record(RecordBytes.KIND_GRID, payload)) }.exceptionOrNull()
            assertWithMessage(name).that(error).isInstanceOf(DjCodecException.Corrupt::class.java)
        }
    }

    @Test
    fun `exceptions are a sealed family`() {
        val errors: List<DjCodecException> = listOf(
            DjCodecException.BadMagic("m"),
            DjCodecException.UnsupportedVersion(2),
            DjCodecException.Corrupt("c"),
        )

        for (error in errors) {
            val kind = when (error) {
                is DjCodecException.BadMagic -> "magic"
                is DjCodecException.UnsupportedVersion -> "version"
                is DjCodecException.Corrupt -> "corrupt"
            }
            assertThat(kind).isNotEmpty()
        }
        assertThat(errors[1].message).contains("2")
    }
}
