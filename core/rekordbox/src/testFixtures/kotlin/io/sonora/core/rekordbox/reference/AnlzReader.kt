package io.sonora.core.rekordbox.reference

import io.sonora.core.rekordbox.anlz.AnlzBeat
import java.nio.file.Files
import java.nio.file.Path

/**
 * A section of an ANLZ file as stored: its fourcc, `len_header` and every byte of it (common header included).
 * [fileOffset] is where the section starts in the file, for error messages. Reads past the section's end throw
 * [AnlzFormatException].
 */
class RawSection(val fourCc: String, val lenHeader: Int, val bytes: ByteArray, val fileOffset: Int = 0) {
    val lenTag: Int get() = bytes.size

    /** Unsigned byte at [offset] from the start of the section. */
    fun u8(offset: Int): Int {
        check(offset, 1)
        return bytes[offset].toInt() and 0xFF
    }

    /** Big-endian u16 at [offset] from the start of the section. */
    fun u16(offset: Int): Int {
        check(offset, 2)
        return (u8(offset) shl 8) or u8(offset + 1)
    }

    /** Big-endian u32 at [offset] from the start of the section. */
    fun u32(offset: Int): Long {
        check(offset, 4)
        return (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
    }

    /** The payload after the section header. */
    fun body(): ByteArray = bytes.copyOfRange(lenHeader, bytes.size)

    internal fun check(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) {
            throw AnlzFormatException(
                "$fourCc section at $fileOffset: $length bytes at +$offset lie past its end (${bytes.size} bytes)",
                fileOffset + offset.coerceAtLeast(0),
                truncated = true,
            )
        }
    }
}

/** A parsed ANLZ file at the section level: its `PMAI` header (as stored) and its sections in file order. */
class RawAnlz(val header: ByteArray, val sections: List<RawSection>) {
    fun all(fourCc: String): List<RawSection> = sections.filter { it.fourCc == fourCc }

    fun single(fourCc: String): RawSection = all(fourCc).single()
}

/**
 * Reader of rekordbox analysis files (`ANLZ0000.DAT`, `.EXT`, `.2EX`), independent of this repository's writer so it
 * can check it, and usable on files written by rekordbox itself (the calibration tool compares Sonora's analysis with
 * them). Everything is big-endian.
 *
 * Formats, as published:
 * - Deep Symmetry, "Rekordbox Export Structure Analysis", Analysis Files:
 *   https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html (the `PMAI` header, `PPTH`, `PQTZ`, `PWAV`,
 *   `PWV2`..`PWV7`, `PCOB`/`PCPT`, `PCO2`/`PCP2`, `PSSI` and its XOR mask).
 * - crate-digger's Kaitai Struct `rekordbox_anlz.ksy` (https://github.com/Deep-Symmetry/crate-digger), the exact field
 *   offsets and the rule that tells a masked `PSSI` from an unmasked one.
 * - rekordcrate (https://github.com/Holzhaus/rekordcrate) and pyrekordbox's `anlz/structs.py`
 *   (https://github.com/dylanljones/pyrekordbox), whose `PQT2` structure documents the CDJ-3000 beat grid.
 *
 * [read] gives typed [AnlzTag]s; [readRaw] only splits the file into sections. Sections of an unknown type come back as
 * [UnknownTag] with all their bytes. Bytes after the length the `PMAI` header declares are ignored.
 */
object AnlzReader {
    private const val MAGIC = "PMAI"
    private const val MIN_FILE_HEADER = 12
    private const val MIN_SECTION_HEADER = 12

    /** Parses [file] into typed tags. @throws AnlzFormatException when the file is not a well-formed ANLZ file. */
    fun read(file: ByteArray): AnlzFile {
        val raw = readRaw(file)
        return AnlzFile(raw, raw.sections.map(AnlzTagParser::parse))
    }

    /** Reads and parses the file at [path]. */
    fun read(path: Path): AnlzFile = read(Files.readAllBytes(path))

    /**
     * Splits [file] into its sections, checking the declared lengths: the file length against the bytes there are, every
     * `len_tag` against `len_header` and the end of the file.
     */
    fun readRaw(file: ByteArray): RawAnlz {
        if (file.size < MAGIC.length) throw AnlzFormatException("file of ${file.size} bytes is too short", 0, truncated = true)
        if (String(file, 0, 4, Charsets.US_ASCII) != MAGIC) throw AnlzFormatException("not an ANLZ file: no PMAI magic", 0)
        if (file.size < MIN_FILE_HEADER) throw AnlzFormatException("PMAI header cut off at ${file.size} bytes", file.size, true)
        val headerLength = u32(file, 4)
        val declaredLength = u32(file, 8)
        if (headerLength < MIN_FILE_HEADER || declaredLength < headerLength) {
            throw AnlzFormatException("PMAI header lengths $headerLength/$declaredLength are impossible", 4)
        }
        if (declaredLength > file.size) {
            throw AnlzFormatException("file is $declaredLength bytes according to its header but only ${file.size} are there", file.size, true)
        }
        val end = declaredLength.toInt()
        val sections = mutableListOf<RawSection>()
        var offset = headerLength.toInt()
        while (offset < end) {
            if (offset + MIN_SECTION_HEADER > end) {
                throw AnlzFormatException("section header cut off at $offset", offset)
            }
            val fourCc = fourCcAt(file, offset)
            val lenHeader = u32(file, offset + 4)
            val lenTag = u32(file, offset + 8)
            if (lenHeader < MIN_SECTION_HEADER || lenTag < lenHeader || offset + lenTag > end) {
                throw AnlzFormatException("$fourCc at $offset has impossible lengths $lenHeader/$lenTag", offset)
            }
            sections += RawSection(fourCc, lenHeader.toInt(), file.copyOfRange(offset, offset + lenTag.toInt()), offset)
            offset += lenTag.toInt()
        }
        return RawAnlz(file.copyOfRange(0, headerLength.toInt()), sections)
    }

    /** The beats of a `PQTZ` section. */
    fun beats(pqtz: RawSection): List<AnlzBeat> = (AnlzTagParser.parse(pqtz) as BeatGridTag).beats

    /** The path of a `PPTH` section, without the terminating NUL. */
    fun path(ppth: RawSection): String = (AnlzTagParser.parse(ppth) as PathTag).path

    private fun fourCcAt(file: ByteArray, offset: Int): String {
        val code = file.copyOfRange(offset, offset + 4)
        if (code.any { it.toInt() !in 0x20..0x7E }) {
            throw AnlzFormatException("section tag at $offset is not four printable characters", offset)
        }
        return String(code, Charsets.US_ASCII)
    }

    private fun u32(file: ByteArray, at: Int): Long =
        file.copyOfRange(at, at + 4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
}

/** Hex string to bytes, spaces ignored: `hex("50 4d 41 49")`. */
fun hex(value: String): ByteArray = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
