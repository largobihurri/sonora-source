package io.sonora.core.rekordbox.reference

import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.core.rekordbox.anlz.CueListType

/** Turns one [RawSection] into its typed [AnlzTag]; offsets and sizes follow the layouts cited on [AnlzReader]. */
internal object AnlzTagParser {
    private const val NO_LOOP = 0xFFFF_FFFFL

    fun parse(section: RawSection): AnlzTag = when (section.fourCc) {
        "PPTH" -> path(section)
        "PQTZ" -> beatGrid(section)
        "PQT2" -> extendedBeatGrid(section)
        "PWAV", "PWV2" -> preview(section)
        "PWV3" -> detail(section)
        "PWV4" -> colorPreview(section)
        "PWV5" -> colorDetail(section)
        "PWV6", "PWV7" -> threeBand(section)
        "PCOB" -> cueList(section)
        "PCO2" -> extendedCueList(section)
        "PSSI" -> songStructure(section)
        else -> UnknownTag(section)
    }

    private fun RawSection.requireHeader(minimum: Int) {
        if (lenHeader < minimum) {
            throw AnlzFormatException("$fourCc at $fileOffset: header is $lenHeader bytes, at least $minimum expected", fileOffset)
        }
    }

    /** [count] entries of [entryBytes] bytes right after the header must fit in the section. */
    private fun RawSection.requireEntries(count: Long, entryBytes: Long) {
        val available = (lenTag - lenHeader).toLong()
        if (count * entryBytes > available) {
            throw AnlzFormatException(
                "$fourCc at $fileOffset: $count entries of $entryBytes bytes do not fit in $available bytes",
                fileOffset + lenHeader,
                truncated = true,
            )
        }
    }

    private fun RawSection.dataAfterHeader(length: Long): ByteArray {
        requireEntries(length, 1)
        return bytes.copyOfRange(lenHeader, lenHeader + length.toInt())
    }

    private fun RawSection.utf16(offset: Int, length: Int): String {
        check(offset, length)
        return String(bytes, offset, length - length % 2, Charsets.UTF_16BE).trimEnd('\u0000')
    }

    private fun path(section: RawSection): PathTag {
        section.requireHeader(0x10)
        val length = section.u32(0x0C)
        section.requireEntries(length, 1)
        return PathTag(section, section.utf16(section.lenHeader, length.toInt()))
    }

    private fun beatGrid(section: RawSection): BeatGridTag {
        section.requireHeader(0x18)
        val count = section.u32(0x14)
        section.requireEntries(count, BEAT_BYTES.toLong())
        val beats = List(count.toInt()) { i ->
            val at = section.lenHeader + i * BEAT_BYTES
            try {
                AnlzBeat(section.u16(at), section.u16(at + 2), section.u32(at + 4))
            } catch (e: IllegalArgumentException) {
                throw AnlzFormatException("PQTZ beat $i: ${e.message}", section.fileOffset + at)
            }
        }
        return BeatGridTag(section, section.u32(0x0C), section.u32(0x10), beats)
    }

    private fun extendedBeatGrid(section: RawSection): ExtendedBeatGridTag {
        section.requireHeader(0x38)
        val count = section.u32(0x28)
        section.requireEntries(count, 2)
        val anchors = listOf(0x18, 0x20).map { GridAnchor(section.u16(it), section.u16(it + 2), section.u32(it + 4)) }
        val entries = List(count.toInt()) { i -> section.u16(section.lenHeader + 2 * i) }
        return ExtendedBeatGridTag(
            section, section.u32(0x10), anchors, section.u32(0x2C), section.u32(0x30), section.u32(0x34), entries,
        )
    }

    private fun preview(section: RawSection): WaveformPreviewTag {
        section.requireHeader(0x14)
        val columns = section.dataAfterHeader(section.u32(0x0C))
        return WaveformPreviewTag(section, columns, section.u32(0x10))
    }

    /** Entry size and count of the waveform tags that carry them (`len_entry_bytes` at 0x0C, `len_entries` at 0x10). */
    private class Entries(val entryBytes: Int, val data: ByteArray)

    private fun entries(section: RawSection): Entries {
        section.requireHeader(0x14)
        val entryBytes = section.u32(0x0C)
        val count = section.u32(0x10)
        if (entryBytes > MAX_ENTRY_BYTES) {
            throw AnlzFormatException("${section.fourCc} at ${section.fileOffset}: entries of $entryBytes bytes", section.fileOffset + 0x0C)
        }
        section.requireEntries(count, entryBytes)
        return Entries(entryBytes.toInt(), section.dataAfterHeader(count * entryBytes))
    }

    private fun trailingUnknown(section: RawSection): Long? = if (section.lenHeader >= 0x18) section.u32(0x14) else null

    private fun detail(section: RawSection): WaveformDetailTag {
        section.requireHeader(0x18)
        return WaveformDetailTag(section, entries(section).data, section.u32(0x14))
    }

    private fun colorPreview(section: RawSection): ColorPreviewTag {
        section.requireHeader(0x18)
        val entries = entries(section)
        return ColorPreviewTag(section, entries.entryBytes, section.u32(0x14), entries.data)
    }

    private fun colorDetail(section: RawSection): ColorDetailTag {
        section.requireHeader(0x18)
        val entries = entries(section)
        if (entries.entryBytes != 2) {
            throw AnlzFormatException("PWV5 at ${section.fileOffset}: entries of ${entries.entryBytes} bytes, 2 expected", section.fileOffset + 0x0C)
        }
        val values = IntArray(entries.data.size / 2) { i ->
            ((entries.data[2 * i].toInt() and 0xFF) shl 8) or (entries.data[2 * i + 1].toInt() and 0xFF)
        }
        return ColorDetailTag(section, section.u32(0x14), values)
    }

    private fun threeBand(section: RawSection): ThreeBandWaveformTag {
        val entries = entries(section)
        if (entries.entryBytes < THREE_BANDS) {
            throw AnlzFormatException("${section.fourCc} at ${section.fileOffset}: entries of ${entries.entryBytes} bytes, 3 expected", section.fileOffset + 0x0C)
        }
        return ThreeBandWaveformTag(section, entries.entryBytes, trailingUnknown(section), entries.data)
    }

    private fun cueListType(section: RawSection): CueListType {
        val code = section.u32(0x0C)
        return CueListType.entries.firstOrNull { it.code.toLong() == code }
            ?: throw AnlzFormatException("${section.fourCc} at ${section.fileOffset}: cue list type $code", section.fileOffset + 0x0C)
    }

    private fun loopTime(value: Long): Long? = if (value == NO_LOOP) null else value

    private fun cueList(section: RawSection): CueListTag {
        section.requireHeader(0x18)
        val count = section.u16(0x12)
        var at = section.lenHeader
        val cues = List(count) {
            val length = entryLength(section, at, "PCPT", minimum = 0x28)
            val cue = Cue(
                hotCue = section.u32(at + 0x0C).toInt(),
                status = section.u32(at + 0x10).toInt(),
                orderFirst = section.u16(at + 0x18),
                orderLast = section.u16(at + 0x1A),
                typeCode = section.u8(at + 0x1C),
                timeMs = section.u32(at + 0x20),
                loopTimeMs = loopTime(section.u32(at + 0x24)),
            )
            at += length
            cue
        }
        return CueListTag(section, cueListType(section), section.u16(0x10), section.u32(0x14), cues)
    }

    /** Checks the entry at [at] (magic, length) and returns its `len_entry`. */
    private fun entryLength(section: RawSection, at: Int, magic: String, minimum: Int): Int {
        section.check(at, 12)
        val found = String(section.bytes, at, 4, Charsets.US_ASCII)
        if (found != magic) {
            throw AnlzFormatException("${section.fourCc} at ${section.fileOffset}: expected $magic at +$at, found '$found'", section.fileOffset + at)
        }
        val length = section.u32(at + 8)
        if (length < minimum) {
            throw AnlzFormatException("$magic at ${section.fileOffset + at} is $length bytes, at least $minimum expected", section.fileOffset + at)
        }
        section.check(at, length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return length.toInt()
    }

    private fun extendedCueList(section: RawSection): ExtendedCueListTag {
        section.requireHeader(0x14)
        val count = section.u16(0x10)
        var at = section.lenHeader
        val cues = List(count) {
            val length = entryLength(section, at, "PCP2", minimum = 0x28)
            val cue = extendedCue(section, at, length)
            at += length
            cue
        }
        return ExtendedCueListTag(section, cueListType(section), section.u16(0x12), cues)
    }

    /** One `PCP2` at [at]; entries cut before their comment or their colour (rekordbox writes some) lack those fields. */
    private fun extendedCue(section: RawSection, at: Int, length: Int): ExtendedCue {
        val commentLength = if (length > 0x2B) section.u32(at + 0x28).toInt() else 0
        if (length > 0x2B && (commentLength < 0 || 0x2C + commentLength > length)) {
            throw AnlzFormatException("PCP2 at ${section.fileOffset + at}: comment of $commentLength bytes in $length", section.fileOffset + at + 0x28, truncated = true)
        }
        val colorAt = at + 0x2C + commentLength
        fun color(extra: Int): Int? = if (length - commentLength > 0x2C + extra) section.u8(colorAt + extra) else null
        return ExtendedCue(
            hotCue = section.u32(at + 0x0C).toInt(),
            typeCode = section.u8(at + 0x10),
            timeMs = section.u32(at + 0x14),
            loopTimeMs = loopTime(section.u32(at + 0x18)),
            colorId = section.u8(at + 0x1C),
            loopNumerator = section.u16(at + 0x24),
            loopDenominator = section.u16(at + 0x26),
            comment = if (commentLength > 0) section.utf16(at + 0x2C, commentLength) else "",
            colorCode = color(0),
            red = color(1),
            green = color(2),
            blue = color(3),
        )
    }

    private fun songStructure(section: RawSection): SongStructureTag {
        section.requireHeader(0x20)
        val entryBytes = section.u32(0x0C)
        if (entryBytes < PHRASE_BYTES) {
            throw AnlzFormatException("PSSI at ${section.fileOffset}: entries of $entryBytes bytes, at least $PHRASE_BYTES expected", section.fileOffset + 0x0C)
        }
        val masked = SongStructureMask.looksMasked(section.bytes)
        val plain = if (masked) SongStructureMask.xor(section.bytes) else section.bytes.copyOf()
        val view = RawSection(section.fourCc, section.lenHeader, plain, section.fileOffset)
        val count = view.u16(0x10)
        view.requireEntries(count.toLong(), entryBytes)
        val phrases = List(count) { i ->
            val at = view.lenHeader + i * entryBytes.toInt()
            Phrase(
                index = view.u16(at), beat = view.u16(at + 2), kind = view.u16(at + 4),
                k1 = view.u8(at + 7), k2 = view.u8(at + 9), b = view.u8(at + 0x0B),
                beat2 = view.u16(at + 0x0C), beat3 = view.u16(at + 0x0E), beat4 = view.u16(at + 0x10),
                k3 = view.u8(at + 0x13), fill = view.u8(at + 0x15), beatFill = view.u16(at + 0x16),
            )
        }
        return SongStructureTag(
            section, masked, mood = view.u16(0x12), endBeat = view.u16(0x1A), bank = view.u8(0x1E), phrases = phrases, unmasked = plain,
        )
    }

    private const val BEAT_BYTES = 8
    private const val THREE_BANDS = 3
    private const val MAX_ENTRY_BYTES = 64L
    private const val PHRASE_BYTES = 0x18L
}
