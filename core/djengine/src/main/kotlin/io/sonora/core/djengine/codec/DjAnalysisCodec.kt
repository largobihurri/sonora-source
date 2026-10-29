package io.sonora.core.djengine.codec

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The binary record format `SDJ1` of the DJ analyses: the grid and the waveform are the BLOBs of the permanent store
 * (D1.7), and the backup record is the compact form the app backup carries (D1.23, base64 by that layer).
 *
 * ## Layout (frozen; all integers BIG-endian, doubles as their IEEE-754 bits, so they round-trip bit for bit)
 *
 * ```
 * offset  size  field
 * 0       4     magic "SDJ1" (53 44 4A 31)
 * 4       2     format version u16 (1)
 * 6       1     kind u8: 1 grid, 2 waveform, 3 backup
 * 7       n     payload, by kind (below)
 * 7+n     4     CRC-32 (java.util.zip.CRC32) of every byte before it, u32
 * ```
 *
 * Grid payload: `count u32` (at least 1), then `count` segments of `startMs f64, bpm f64, beatInBarAtStart u8`
 * (17 bytes each). Uncompressed: a grid is a few dozen bytes, a per-beat grid of a long track a few KiB.
 *
 * Waveform payload: `algorithm version u32` ([BandWaveform.version]), `column count u32`, `compressed length u32`,
 * then a zlib stream (RFC 1950, [Deflater] level 6) of the four planes one after another in the order low, mid, high,
 * peak, each `column count` bytes. Only the DECODED content is frozen: the compressed bytes depend on the zlib build,
 * so never compare stored blobs byte by byte.
 *
 * Backup payload (no waveform: backups exclude it, it is recomputed from the audio):
 * ```
 * audio id            string                 (u16 byte length + UTF-8)
 * sample rate         u32                    (1..Int.MAX_VALUE)
 * total frames        i64                    (0 or more)
 * flags               u8                     bit0 grid present, bit1 key present, other bits zero
 * [grid]              count u32 + segments   (as in the grid payload) when bit0
 * [key]               key index u8 (1..24, MusicalKey.index), strength f64, method string, when bit1
 * analyzer version    u32                    (1 or more)
 * beat source         u8                     0 MODEL, 1 QM_DSP
 * provenance flags    u8                     bit0 model id present, bit1 accelerator present, other bits zero
 * [model id]          string                 when bit0
 * [accelerator]       u8                     0 CPU, 1 GPU, 2 NPU, when bit1
 * analysed at         i64                    epoch milliseconds
 * ```
 * Enum values are written as the explicit codes above, never as ordinals, so reordering a Kotlin enum cannot change
 * the format.
 *
 * ## Rules
 * - A decoder returns exactly what was encoded or throws a [DjCodecException]; every field is validated, the payload
 *   must end exactly where its lengths say, and the checksum must match.
 * - A format version above [FORMAT_VERSION] is [DjCodecException.UnsupportedVersion] (checked before anything else
 *   about the body, since a newer layout may differ): the store keeps such bytes and a newer build reads them.
 * - Format changes that old readers could misread need a new version; additions are not possible inside v1.
 */
object DjAnalysisCodec {

    /** The newest record format this build reads and the one it writes. */
    const val FORMAT_VERSION: Int = 1

    private const val KIND_GRID = 1
    private const val KIND_WAVEFORM = 2
    private const val KIND_BACKUP = 3

    private val MAGIC = byteArrayOf(0x53, 0x44, 0x4A, 0x31)
    private const val HEADER_SIZE = 7
    private const val CRC_SIZE = 4
    private const val SEGMENT_SIZE = 17
    private const val ZLIB_LEVEL = 6
    private const val PLANES = 4

    /** 24 hours of columns: far beyond any track, small enough that a corrupt count cannot ask for gigabytes. */
    private const val MAX_COLUMNS = 24 * 3600 * BandWaveform.COLUMNS_PER_SECOND

    private const val FLAG_GRID = 1
    private const val FLAG_KEY = 2
    private const val FLAG_MODEL_ID = 1
    private const val FLAG_ACCELERATOR = 2

    fun encodeGrid(grid: DjBeatGrid): ByteArray {
        val payload = Writer()
        payload.segments(grid)
        return frame(KIND_GRID, payload.toByteArray())
    }

    /** @throws DjCodecException unless [bytes] is a valid grid record. */
    fun decodeGrid(bytes: ByteArray): DjBeatGrid {
        val reader = open(bytes, KIND_GRID)
        val grid = reader.segments()
        reader.requireEnd()
        return grid
    }

    fun encodeWaveform(waveform: BandWaveform): ByteArray {
        val columns = waveform.columnCount
        require(columns <= MAX_COLUMNS) { "A waveform of $columns columns is longer than 24 hours" }
        val raw = ByteArray(PLANES * columns)
        waveform.low().copyInto(raw, 0)
        waveform.mid().copyInto(raw, columns)
        waveform.high().copyInto(raw, 2 * columns)
        waveform.peak().copyInto(raw, 3 * columns)
        val compressed = deflate(raw)
        val payload = Writer()
        payload.u32(waveform.version.toLong()).u32(columns.toLong()).u32(compressed.size.toLong()).raw(compressed)
        return frame(KIND_WAVEFORM, payload.toByteArray())
    }

    /** @throws DjCodecException unless [bytes] is a valid waveform record. */
    fun decodeWaveform(bytes: ByteArray): BandWaveform {
        val reader = open(bytes, KIND_WAVEFORM)
        val version = reader.u32()
        val columns = reader.u32()
        val compressedLength = reader.u32()
        if (columns > MAX_COLUMNS) throw DjCodecException.Corrupt("Waveform column count $columns is out of range")
        if (compressedLength != reader.remaining().toLong()) {
            throw DjCodecException.Corrupt("Waveform stream length $compressedLength does not match the ${reader.remaining()} bytes present")
        }
        val raw = inflate(reader.raw(compressedLength.toInt()), PLANES * columns.toInt())
        reader.requireEnd()
        val n = columns.toInt()
        return reader.guarded("waveform") {
            BandWaveform(
                raw.copyOfRange(0, n),
                raw.copyOfRange(n, 2 * n),
                raw.copyOfRange(2 * n, 3 * n),
                raw.copyOfRange(3 * n, 4 * n),
                version.toInt(),
            )
        }
    }

    fun encodeBackup(record: DjBackupRecord): ByteArray {
        val p = Writer()
        p.string(record.audioId.value).u32(record.sampleRate.toLong()).i64(record.totalFrames)
        val grid = record.grid
        val key = record.key
        p.u8((if (grid != null) FLAG_GRID else 0) or (if (key != null) FLAG_KEY else 0))
        if (grid != null) p.segments(grid)
        if (key != null) p.u8(key.key.index).f64(key.strength).string(key.method)
        val provenance = record.provenance
        p.u32(provenance.analyzerVersion.toLong())
        p.u8(beatSourceCode(provenance.beatSource))
        val modelId = provenance.modelId
        val accelerator = provenance.accelerator
        p.u8((if (modelId != null) FLAG_MODEL_ID else 0) or (if (accelerator != null) FLAG_ACCELERATOR else 0))
        if (modelId != null) p.string(modelId)
        if (accelerator != null) p.u8(acceleratorCode(accelerator))
        p.i64(provenance.analysedAtEpochMs)
        return frame(KIND_BACKUP, p.toByteArray())
    }

    /** @throws DjCodecException unless [bytes] is a valid backup record. */
    fun decodeBackup(bytes: ByteArray): DjBackupRecord {
        val r = open(bytes, KIND_BACKUP)
        val record = r.guarded("backup record") {
            val id = r.string()
            val audioId = AudioId.parse(id) ?: throw DjCodecException.Corrupt("Not an audio id: '$id'")
            val sampleRate = r.u32()
            if (sampleRate !in 1..Int.MAX_VALUE.toLong()) throw DjCodecException.Corrupt("Sample rate out of range: $sampleRate")
            val totalFrames = r.i64()
            val flags = r.u8()
            if (flags and (FLAG_GRID or FLAG_KEY).inv() != 0) throw DjCodecException.Corrupt("Unknown backup flags $flags")
            val grid = if (flags and FLAG_GRID != 0) r.segments() else null
            val key = if (flags and FLAG_KEY != 0) {
                val index = r.u8()
                val musicalKey = MusicalKey.fromIndex(index) ?: throw DjCodecException.Corrupt("Unknown key index $index")
                KeyEstimate(musicalKey, r.f64(), r.string())
            } else {
                null
            }
            val analyzerVersion = r.u32()
            if (analyzerVersion !in 1..Int.MAX_VALUE.toLong()) throw DjCodecException.Corrupt("Analyzer version out of range: $analyzerVersion")
            val beatSource = beatSourceOf(r.u8())
            val provenanceFlags = r.u8()
            if (provenanceFlags and (FLAG_MODEL_ID or FLAG_ACCELERATOR).inv() != 0) {
                throw DjCodecException.Corrupt("Unknown provenance flags $provenanceFlags")
            }
            val modelId = if (provenanceFlags and FLAG_MODEL_ID != 0) r.string() else null
            val accelerator = if (provenanceFlags and FLAG_ACCELERATOR != 0) acceleratorOf(r.u8()) else null
            val provenance = AnalysisProvenance(analyzerVersion.toInt(), beatSource, modelId, accelerator, r.i64())
            DjBackupRecord(audioId, sampleRate.toInt(), totalFrames, grid, key, provenance)
        }
        r.requireEnd()
        return record
    }

    // Framing

    private fun frame(kind: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HEADER_SIZE + payload.size + CRC_SIZE)
        out.put(MAGIC).putShort(FORMAT_VERSION.toShort()).put(kind.toByte()).put(payload)
        val crc = CRC32().apply { update(out.array(), 0, out.position()) }.value
        out.putInt(crc.toInt())
        return out.array()
    }

    /** Validates magic, version, kind and checksum of [bytes] and returns a reader over the payload. */
    private fun open(bytes: ByteArray, expectedKind: Int): Reader {
        for (i in 0 until minOf(bytes.size, MAGIC.size)) {
            if (bytes[i] != MAGIC[i]) throw DjCodecException.BadMagic("Not a DJ record: the magic is not SDJ1")
        }
        if (bytes.size < HEADER_SIZE) throw DjCodecException.Corrupt("Truncated record: ${bytes.size} bytes")
        val version = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (version > FORMAT_VERSION) throw DjCodecException.UnsupportedVersion(version)
        if (version < 1) throw DjCodecException.Corrupt("Invalid format version $version")
        if (bytes.size < HEADER_SIZE + CRC_SIZE) throw DjCodecException.Corrupt("Truncated record: ${bytes.size} bytes")
        val crcStart = bytes.size - CRC_SIZE
        val stored = ByteBuffer.wrap(bytes, crcStart, CRC_SIZE).int
        val actual = CRC32().apply { update(bytes, 0, crcStart) }.value.toInt()
        if (stored != actual) throw DjCodecException.Corrupt("Checksum mismatch")
        val kind = bytes[6].toInt() and 0xFF
        if (kind != expectedKind) throw DjCodecException.Corrupt("Record kind $kind where kind $expectedKind was expected")
        return Reader(bytes, HEADER_SIZE, crcStart)
    }

    // Waveform compression

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(ZLIB_LEVEL)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 8 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates exactly [expectedSize] bytes; a short stream, a long stream or a damaged one is corrupt. */
    private fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val out = ByteArray(expectedSize)
            var filled = 0
            while (filled < expectedSize) {
                val n = inflater.inflate(out, filled, expectedSize - filled)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                filled += n
            }
            if (filled != expectedSize) throw DjCodecException.Corrupt("Waveform stream holds $filled bytes, expected $expectedSize")
            // The stream must end exactly here: no more data and nothing left over.
            val probe = ByteArray(1)
            if (inflater.inflate(probe) != 0 || !inflater.finished() || inflater.remaining != 0) {
                throw DjCodecException.Corrupt("Waveform stream does not end after the planes")
            }
            return out
        } catch (e: DataFormatException) {
            throw DjCodecException.Corrupt("Damaged waveform stream: ${e.message}", e)
        } finally {
            inflater.end()
        }
    }

    // Enum codes (frozen)

    private fun beatSourceCode(source: BeatSource): Int = when (source) {
        BeatSource.MODEL -> 0
        BeatSource.QM_DSP -> 1
    }

    private fun beatSourceOf(code: Int): BeatSource = when (code) {
        0 -> BeatSource.MODEL
        1 -> BeatSource.QM_DSP
        else -> throw DjCodecException.Corrupt("Unknown beat source $code")
    }

    private fun acceleratorCode(accelerator: Accelerator): Int = when (accelerator) {
        Accelerator.CPU -> 0
        Accelerator.GPU -> 1
        Accelerator.NPU -> 2
    }

    private fun acceleratorOf(code: Int): Accelerator = when (code) {
        0 -> Accelerator.CPU
        1 -> Accelerator.GPU
        2 -> Accelerator.NPU
        else -> throw DjCodecException.Corrupt("Unknown accelerator $code")
    }

    // Primitive I/O

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v) }

        fun u16(v: Int) = apply { out.write(v ushr 8); out.write(v) }

        fun u32(v: Long) = apply { for (shift in 24 downTo 0 step 8) out.write((v ushr shift).toInt()) }

        fun i64(v: Long) = apply { for (shift in 56 downTo 0 step 8) out.write((v ushr shift).toInt()) }

        fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))

        fun raw(v: ByteArray) = apply { out.write(v) }

        fun string(v: String) = apply {
            val bytes = v.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 0xFFFF) { "A text field is limited to 65535 UTF-8 bytes" }
            u16(bytes.size)
            out.write(bytes)
        }

        fun segments(grid: DjBeatGrid) = apply {
            u32(grid.segments.size.toLong())
            for (s in grid.segments) f64(s.startMs).f64(s.bpm).u8(s.beatInBarAtStart)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val bytes: ByteArray, private var position: Int, private val end: Int) {

        fun remaining(): Int = end - position

        fun requireEnd() {
            if (position != end) throw DjCodecException.Corrupt("${end - position} unexpected bytes after the record body")
        }

        private fun take(count: Int): Int {
            if (count < 0 || count > end - position) throw DjCodecException.Corrupt("Record ends inside a field")
            val start = position
            position += count
            return start
        }

        fun u8(): Int = bytes[take(1)].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long {
            val start = take(4)
            return ByteBuffer.wrap(bytes, start, 4).int.toLong() and 0xFFFF_FFFFL
        }

        fun i64(): Long {
            val start = take(8)
            return ByteBuffer.wrap(bytes, start, 8).long
        }

        fun f64(): Double = java.lang.Double.longBitsToDouble(i64())

        fun raw(count: Int): ByteArray {
            val start = take(count)
            return bytes.copyOfRange(start, start + count)
        }

        fun string(): String {
            val length = u16()
            val start = take(length)
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw DjCodecException.Corrupt("Invalid UTF-8 text", e)
            }
        }

        fun segments(): DjBeatGrid {
            val count = u32()
            if (count < 1 || count > remaining() / SEGMENT_SIZE) {
                throw DjCodecException.Corrupt("Segment count $count does not fit the record")
            }
            val list = ArrayList<TempoSegment>(count.toInt())
            repeat(count.toInt()) { list += guarded("segment") { TempoSegment(f64(), f64(), u8()) } }
            return guarded("grid") { DjBeatGrid(list) }
        }

        /** Runs [block], turning a rejected field value (the model types validate their own invariants) into corruption. */
        inline fun <T> guarded(what: String, block: () -> T): T = try {
            block()
        } catch (e: IllegalArgumentException) {
            throw DjCodecException.Corrupt("Invalid $what: ${e.message}", e)
        }
    }
}
