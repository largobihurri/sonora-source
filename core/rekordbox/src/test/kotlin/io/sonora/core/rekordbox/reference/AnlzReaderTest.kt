package io.sonora.core.rekordbox.reference

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.rekordbox.DemoTracks
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.core.rekordbox.anlz.AnlzTrack
import io.sonora.core.rekordbox.anlz.AnlzWriter
import io.sonora.core.rekordbox.anlz.BeatGridSection
import io.sonora.core.rekordbox.anlz.CueListType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The typed ANLZ reader: round trips against this repository's own writer, plus small hand-built byte fixtures for the
 * sections the writer does not produce (layouts from Deep Symmetry's analysis and crate-digger's `rekordbox_anlz.ksy`;
 * `PQT2` from pyrekordbox).
 */
class AnlzReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---- round trips against AnlzWriter -------------------------------------------------------------------------

    private val grid = BeatGrid(bpm = 127.5, firstBeatMs = 215.0, firstBeatInBar = 3, beatCount = 40)
    private val waveform = BlueWaveform(
        detail = ByteArray(450) { (it * 7).toByte() },
        preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { (it * 3 + 1).toByte() },
        tinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
    )
    private val track = AnlzTrack("/Contents/Björk/Álbum/日本.aiff", grid, waveform)

    @Test
    fun `a written DAT reads back with every field`() {
        val file = AnlzReader.read(AnlzWriter.writeDat(track))

        assertThat(file.tags.map { it.fourCc })
            .containsExactly("PPTH", "PVBR", "PQTZ", "PWAV", "PWV2", "PCOB", "PCOB").inOrder()
        assertThat(file.path).isEqualTo(track.filePath)

        val beats = file.first<BeatGridTag>()!!
        assertThat(beats.beats).isEqualTo(BeatGridSection.from(grid).beats)
        assertThat(beats.beats).hasSize(40)
        assertThat(beats.beats.first()).isEqualTo(AnlzBeat(3, 12_750, 215))
        assertThat(beats.unknown1).isEqualTo(0)
        assertThat(beats.unknown2).isEqualTo(0x0008_0000)

        val preview = file.single("PWAV") as WaveformPreviewTag
        assertThat(preview.isTiny).isFalse()
        assertThat(preview.columns).isEqualTo(waveform.preview())
        assertThat(preview.unknown).isEqualTo(0x0001_0000)
        assertThat(preview.height(1)).isEqualTo(4 and 0x1F)
        assertThat(preview.whiteness(1)).isEqualTo(4 ushr 5)
        assertThat(preview.height(100)).isEqualTo((301 and 0xFF) and 0x1F)
        assertThat(preview.whiteness(100)).isEqualTo((301 and 0xFF) ushr 5)

        val tiny = file.single("PWV2") as WaveformPreviewTag
        assertThat(tiny.isTiny).isTrue()
        assertThat(tiny.columns).isEqualTo(waveform.tinyPreview())
        assertThat(tiny.height(17)).isEqualTo(1)
        assertThat(tiny.whiteness(17)).isEqualTo(0)

        val cues = file.filter<CueListTag>()
        assertThat(cues.map { it.type }).containsExactly(CueListType.HOT, CueListType.MEMORY).inOrder()
        assertThat(cues.map { it.cues }).containsExactly(emptyList<Cue>(), emptyList<Cue>())
        assertThat(cues.map { it.memoryCount }).containsExactly(0xFFFF_FFFFL, 0xFFFF_FFFFL)

        val vbr = file.single("PVBR")
        assertThat(vbr).isInstanceOf(UnknownTag::class.java)
        assertThat(vbr.raw.bytes).hasLength(1620)
    }

    @Test
    fun `a written EXT reads back with every field`() {
        val file = AnlzReader.read(AnlzWriter.writeExt(track))

        assertThat(file.tags.map { it.fourCc })
            .containsExactly("PPTH", "PWV3", "PCOB", "PCOB", "PCO2", "PCO2").inOrder()
        assertThat(file.path).isEqualTo(track.filePath)

        val detail = file.first<WaveformDetailTag>()!!
        assertThat(detail.columns).isEqualTo(waveform.detail())
        assertThat(detail.unknown).isEqualTo(0x0096_0000)
        assertThat(detail.height(2)).isEqualTo((14 and 0xFF) and 0x1F)
        assertThat(detail.whiteness(2)).isEqualTo(0)

        val extended = file.filter<ExtendedCueListTag>()
        assertThat(extended.map { it.type }).containsExactly(CueListType.HOT, CueListType.MEMORY).inOrder()
        assertThat(extended.map { it.cues }).containsExactly(emptyList<ExtendedCue>(), emptyList<ExtendedCue>())
    }

    @Test
    fun `reading a path gives the same file as reading its bytes`() {
        val dat = folder.newFile("ANLZ0000.DAT").also { it.writeBytes(AnlzWriter.writeDat(track)) }

        val file = AnlzReader.read(dat.toPath())

        assertThat(file.path).isEqualTo(track.filePath)
        assertThat(file.raw.header).isEqualTo(AnlzReader.readRaw(AnlzWriter.writeDat(track)).header)
    }

    @Test
    fun `the demo files of rekordbox read with their path, beats and detail`() {
        val dat = AnlzReader.read(DemoTracks.resource("P016/0000875E/ANLZ0000.DAT"))
        val ext = AnlzReader.read(DemoTracks.resource("P016/0000875E/ANLZ0000.EXT"))

        assertThat(dat.path).isEqualTo("/Contents/Loopmasters/UnknownAlbum/Demo Track 1.mp3")
        assertThat(dat.first<BeatGridTag>()!!.beats).isNotEmpty()
        assertThat(dat.first<BeatGridTag>()!!.beats.first().tempo).isEqualTo(12_800)
        assertThat(ext.first<WaveformDetailTag>()!!.columns.size).isGreaterThan(1000)
        assertThat(dat.tags.filterIsInstance<UnknownTag>().map { it.fourCc }).containsExactly("PVBR")
    }

    // ---- hand-built sections ---------------------------------------------------------------------------------

    @Test
    fun `PWV4 holds six channels per column`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 0x7F, 0x00, 0x10, 0xFF.toByte(), 0x80.toByte(), 0x01)
        val file = AnlzReader.read(anlz(section("PWV4", be { writeInt(6); writeInt(2); writeInt(0x1234) }, data)))

        val preview = file.single("PWV4") as ColorPreviewTag
        assertThat(preview.entryBytes).isEqualTo(6)
        assertThat(preview.columnCount).isEqualTo(2)
        assertThat(preview.unknown).isEqualTo(0x1234)
        assertThat(preview.data).isEqualTo(data)
        assertThat((0 until 6).map { preview.channel(0, it) }).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        assertThat((0 until 6).map { preview.channel(1, it) }).containsExactly(0x7F, 0, 0x10, 255, 128, 1).inOrder()
    }

    @Test
    fun `PWV5 unpacks red, green, blue and height from each 16-bit entry`() {
        val values = listOf(0xE07C, 0x1C04, 0x29C0)
        val body = be { values.forEach { writeShort(it) } }
        val file = AnlzReader.read(anlz(section("PWV5", be { writeInt(2); writeInt(3); writeInt(0x0096_0305) }, body)))

        val detail = file.single("PWV5") as ColorDetailTag
        assertThat(detail.entries.toList()).isEqualTo(values)
        assertThat(detail.unknown).isEqualTo(0x0096_0305)
        assertThat(listOf(detail.red(0), detail.green(0), detail.blue(0), detail.height(0))).containsExactly(7, 0, 0, 31).inOrder()
        assertThat(listOf(detail.red(1), detail.green(1), detail.blue(1), detail.height(1))).containsExactly(0, 7, 0, 1).inOrder()
        assertThat(listOf(detail.red(2), detail.green(2), detail.blue(2), detail.height(2))).containsExactly(1, 2, 3, 16).inOrder()
    }

    @Test
    fun `PWV6 and PWV7 hold low, mid and high heights per column`() {
        val preview = byteArrayOf(10, 20, 30, 40, 50, 60)
        val detail = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val file = AnlzReader.read(
            anlz(
                section("PWV6", be { writeInt(3); writeInt(2) }, preview),
                section("PWV7", be { writeInt(3); writeInt(3); writeInt(0x0096_0000) }, detail),
            ),
        )

        val pwv6 = file.single("PWV6") as ThreeBandWaveformTag
        assertThat(pwv6.isDetail).isFalse()
        assertThat(pwv6.unknown).isNull()
        assertThat(pwv6.columnCount).isEqualTo(2)
        // Byte 0 is the low band, 1 the mid, 2 the high (D1.20: that order correlates best on 150 of 150 tracks).
        assertThat(listOf(pwv6.low(1), pwv6.mid(1), pwv6.high(1))).containsExactly(40, 50, 60).inOrder()

        val pwv7 = file.single("PWV7") as ThreeBandWaveformTag
        assertThat(pwv7.isDetail).isTrue()
        assertThat(pwv7.unknown).isEqualTo(0x0096_0000)
        assertThat(pwv7.columnCount).isEqualTo(3)
        assertThat(listOf(pwv7.low(2), pwv7.mid(2), pwv7.high(2))).containsExactly(7, 8, 9).inOrder()
        assertThat(listOf(pwv7.low(0), pwv7.mid(0), pwv7.high(0))).containsExactly(1, 2, 3).inOrder()
        assertThat(pwv7.data).isEqualTo(detail)
    }

    @Test
    fun `PQT2 reads its anchors and one 16-bit word per beat`() {
        val header = be {
            writeInt(0)
            writeInt(0x0100_0002)
            writeInt(0)
            writeShort(1); writeShort(12_500); writeInt(0)
            writeShort(1); writeShort(12_500); writeInt(322_560)
            writeInt(4)
            writeInt(0x1234_5678); writeInt(0); writeInt(9)
        }
        val body = byteArrayOf(1, 0, 2, 7, 3, 0, 4, 0)

        val tag = AnlzReader.read(anlz(section("PQT2", header, body))).single("PQT2") as ExtendedBeatGridTag

        assertThat(tag.raw.lenHeader).isEqualTo(0x38)
        assertThat(tag.version).isEqualTo(0x0100_0002)
        assertThat(tag.anchors).containsExactly(GridAnchor(1, 12_500, 0), GridAnchor(1, 12_500, 322_560)).inOrder()
        assertThat(tag.unknown1).isEqualTo(0x1234_5678)
        assertThat(tag.unknown2).isEqualTo(0)
        assertThat(tag.unknown3).isEqualTo(9)
        assertThat(tag.entries).containsExactly(0x0100, 0x0207, 0x0300, 0x0400).inOrder()
    }

    @Test
    fun `an empty PQT2 has no entries`() {
        val header = be {
            writeInt(0); writeInt(0x0200_0002); writeInt(0)
            repeat(2) { writeShort(0); writeShort(0); writeInt(0) }
            writeInt(0); writeInt(0); writeInt(0); writeInt(0)
        }

        val tag = AnlzReader.read(anlz(section("PQT2", header))).single("PQT2") as ExtendedBeatGridTag

        assertThat(tag.entries).isEmpty()
        assertThat(tag.version).isEqualTo(0x0200_0002)
    }

    @Test
    fun `PCOB lists memory cues, hot cues and loops with their times`() {
        val hot = pcpt(hotCue = 1, status = 0, first = 0xFFFF, last = 1, type = 1, time = 1_500, loopTime = 0xFFFF_FFFFL)
        val loop = pcpt(hotCue = 0, status = 4, first = 0, last = 0xFFFF, type = 2, time = 8_000, loopTime = 12_000)
        val header = be { writeInt(1); writeShort(0); writeShort(2); writeInt(3) }

        val tag = AnlzReader.read(anlz(section("PCOB", header, hot + loop))).single("PCOB") as CueListTag

        assertThat(tag.type).isEqualTo(CueListType.HOT)
        assertThat(tag.memoryCount).isEqualTo(3)
        assertThat(tag.cues).containsExactly(
            Cue(hotCue = 1, status = 0, orderFirst = 0xFFFF, orderLast = 1, typeCode = 1, timeMs = 1_500, loopTimeMs = null),
            Cue(hotCue = 0, status = 4, orderFirst = 0, orderLast = 0xFFFF, typeCode = 2, timeMs = 8_000, loopTimeMs = 12_000),
        ).inOrder()
        assertThat(tag.cues[1].isLoop).isTrue()
        assertThat(tag.cues[0].isLoop).isFalse()
    }

    @Test
    fun `PCO2 reads hot cue colours, comments, loop sizes and tolerates partial entries`() {
        val hot = pcp2(hotCue = 3, type = 1, time = 30_250, comment = "Drop", color = intArrayOf(0x2A, 0xE0, 0x30, 0x10))
        val loop = pcp2(hotCue = 0, type = 2, time = 64_000, loopTime = 66_000, colorId = 5, numerator = 4, denominator = 1)
        val noColor = pcp2(hotCue = 0, type = 1, time = 100, comment = "Intro")
        val noComment = pcp2(hotCue = 2, type = 1, time = 200, partial = true)
        val header = be { writeInt(1); writeShort(4); writeShort(0) }

        val tag = AnlzReader.read(anlz(section("PCO2", header, hot + loop + noColor + noComment))).single("PCO2") as
            ExtendedCueListTag

        assertThat(tag.type).isEqualTo(CueListType.HOT)
        assertThat(tag.cues[0]).isEqualTo(
            ExtendedCue(3, 1, 30_250, null, 0, 0, 0, "Drop", colorCode = 0x2A, red = 0xE0, green = 0x30, blue = 0x10),
        )
        assertThat(tag.cues[1]).isEqualTo(
            ExtendedCue(0, 2, 64_000, 66_000, 5, 4, 1, "", colorCode = 0, red = 0, green = 0, blue = 0),
        )
        assertThat(tag.cues[1].isLoop).isTrue()
        assertThat(tag.cues[2].comment).isEqualTo("Intro")
        assertThat(tag.cues[2].colorCode).isNull()
        assertThat(tag.cues[3]).isEqualTo(ExtendedCue(2, 1, 200, null, 0, 0, 0, "", null, null, null, null))
    }

    // ---- PSSI -------------------------------------------------------------------------------------------------

    /** The published base pattern, typed here separately from the reader's so the two can disagree. */
    private val publishedPattern = "CB E1 EE FA E5 EE AD EE E9 D2 E9 EB E1 E9 F3 E8 E9 F4 E1".split(" ").map { it.toInt(16) }

    /** XORs bytes 0x12.. of [section] with the published key for [lenEntries] entries, computed byte by byte. */
    private fun mask(section: ByteArray, lenEntries: Int): ByteArray {
        val out = section.copyOf()
        for (i in 0x12 until out.size) {
            val key = (publishedPattern[(i - 0x12) % 19] + lenEntries) and 0xFF
            out[i] = (out[i].toInt() xor key).toByte()
        }
        return out
    }

    private fun pssiPhrase(index: Int, beat: Int, kind: Int, k: Triple<Int, Int, Int> = Triple(0, 0, 0), b: Int = 0,
        beats: Triple<Int, Int, Int> = Triple(0, 0, 0), fill: Int = 0, beatFill: Int = 0): ByteArray = be {
        writeShort(index); writeShort(beat); writeShort(kind)
        writeByte(0); writeByte(k.first); writeByte(0); writeByte(k.second); writeByte(0); writeByte(b)
        writeShort(beats.first); writeShort(beats.second); writeShort(beats.third)
        writeByte(0); writeByte(k.third); writeByte(0); writeByte(fill); writeShort(beatFill)
    }

    private fun pssi(mood: Int, endBeat: Int, bank: Int, phrases: List<ByteArray>): ByteArray {
        val header = be {
            writeInt(24)
            writeShort(phrases.size)
            writeShort(mood)
            write(ByteArray(6) { 0 })
            writeShort(endBeat)
            writeShort(0)
            writeByte(bank)
            writeByte(0)
        }
        return section("PSSI", header, phrases.fold(ByteArray(0)) { a, p -> a + p })
    }

    private val threePhrases = listOf(
        pssiPhrase(1, 1, 1, k = Triple(1, 0, 1)),
        pssiPhrase(2, 17, 2, k = Triple(0, 1, 0), b = 1, beats = Triple(20, 24, 28), fill = 1, beatFill = 31),
        pssiPhrase(3, 33, 6, fill = 0),
    )

    private fun checkThreePhrases(tag: SongStructureTag) {
        assertThat(tag.mood).isEqualTo(1)
        assertThat(tag.endBeat).isEqualTo(97)
        assertThat(tag.bank).isEqualTo(3)
        assertThat(tag.phrases).containsExactly(
            Phrase(1, 1, 1, k1 = 1, k2 = 0, k3 = 1, b = 0, beat2 = 0, beat3 = 0, beat4 = 0, fill = 0, beatFill = 0),
            Phrase(2, 17, 2, k1 = 0, k2 = 1, k3 = 0, b = 1, beat2 = 20, beat3 = 24, beat4 = 28, fill = 1, beatFill = 31),
            Phrase(3, 33, 6, k1 = 0, k2 = 0, k3 = 0, b = 0, beat2 = 0, beat3 = 0, beat4 = 0, fill = 0, beatFill = 0),
        ).inOrder()
    }

    @Test
    fun `an unmasked PSSI reads as stored`() {
        val plain = pssi(mood = 1, endBeat = 97, bank = 3, phrases = threePhrases)

        val tag = AnlzReader.read(anlz(plain)).single("PSSI") as SongStructureTag

        assertThat(tag.wasMasked).isFalse()
        checkThreePhrases(tag)
        assertThat(tag.unmasked).isEqualTo(plain)
    }

    @Test
    fun `a masked PSSI is unmasked with the published pattern plus the entry count`() {
        val plain = pssi(mood = 1, endBeat = 97, bank = 3, phrases = threePhrases)
        val masked = mask(plain, lenEntries = 3)
        // Worked by hand: mood 0x0001 is stored as 00^(CB+3) 01^(E1+3) = CE E5; len_entries (0x10) is not masked.
        assertThat(masked.copyOfRange(0x10, 0x14)).isEqualTo(hex("0003 cee5"))
        assertThat(masked.copyOfRange(0, 0x12)).isEqualTo(plain.copyOfRange(0, 0x12))

        val tag = AnlzReader.read(anlz(masked)).single("PSSI") as SongStructureTag

        assertThat(tag.wasMasked).isTrue()
        checkThreePhrases(tag)
        assertThat(tag.unmasked).isEqualTo(plain)
        assertThat(tag.raw.bytes).isEqualTo(masked)
    }

    @Test
    fun `the mask wraps modulo 256 and cycles through its 19 bytes over many entries`() {
        val phrases = List(70) { pssiPhrase(it + 1, 1 + 8 * it, 2 + it % 9, fill = it % 2, beatFill = 4 * it) }
        val plain = pssi(mood = 2, endBeat = 8 * 70 + 1, bank = 8, phrases = phrases)
        val masked = mask(plain, lenEntries = 70)
        // 0xCB + 70 = 0x111: the first key byte wraps to 0x11.
        assertThat(masked[0x12].toInt() and 0xFF).isEqualTo(0x00 xor 0x11)

        val tag = AnlzReader.read(anlz(masked)).single("PSSI") as SongStructureTag

        assertThat(tag.wasMasked).isTrue()
        assertThat(tag.mood).isEqualTo(2)
        assertThat(tag.bank).isEqualTo(8)
        assertThat(tag.phrases).hasSize(70)
        assertThat(tag.phrases[69]).isEqualTo(Phrase(70, 553, 2 + 69 % 9, 0, 0, 0, 0, 0, 0, 0, 1, 276))
        assertThat(tag.unmasked).isEqualTo(plain)
    }

    @Test
    fun `SongStructureMask masks, unmasks and recognises a masked tag`() {
        val plain = pssi(mood = 3, endBeat = 5, bank = 0, phrases = threePhrases)
        val masked = mask(plain, lenEntries = 3)

        assertThat(SongStructureMask.looksMasked(plain)).isFalse()
        assertThat(SongStructureMask.looksMasked(masked)).isTrue()
        assertThat(SongStructureMask.xor(masked)).isEqualTo(plain)
        assertThat(SongStructureMask.xor(plain)).isEqualTo(masked)
        assertThat(SongStructureMask.PATTERN.toList()).isEqualTo(publishedPattern)
    }

    // ---- unknown tags and errors -------------------------------------------------------------------------------

    @Test
    fun `an unknown tag is kept whole and in place`() {
        val unknown = section("PXYZ", hex("0102 0304"), hex("aabbcc"))
        val file = AnlzReader.read(anlz(section("PPTH", be { writeInt(4) }, "/\u0000".toByteArray(Charsets.UTF_16BE)), unknown))

        assertThat(file.tags.map { it.fourCc }).containsExactly("PPTH", "PXYZ").inOrder()
        val tag = file.tags[1] as UnknownTag
        assertThat(tag.raw.bytes).isEqualTo(unknown)
        assertThat(tag.raw.lenHeader).isEqualTo(16)
        assertThat(tag.raw.body()).isEqualTo(hex("aabbcc"))
    }

    @Test
    fun `a file cut anywhere inside its sections is a truncated file`() {
        val full = AnlzWriter.writeDat(track)

        for (cut in listOf(0, 3, 11, 27, 28, 35, 100, 1_000, full.size - 1)) {
            val failure = assertThrows(AnlzFormatException::class.java) { AnlzReader.read(full.copyOf(cut)) }
            assertThat(failure.truncated).isTrue()
        }
    }

    @Test
    fun `a file that is not a PMAI file is refused without being called truncated`() {
        val bytes = AnlzWriter.writeDat(track).also { it[0] = 'X'.code.toByte() }

        val failure = assertThrows(AnlzFormatException::class.java) { AnlzReader.read(bytes) }

        assertThat(failure.truncated).isFalse()
        assertThat(failure.offset).isEqualTo(0)
    }

    @Test
    fun `a section whose length is smaller than its header is refused`() {
        val bad = hex("50505448 00000010 00000004 00000000")
        val failure = assertThrows(AnlzFormatException::class.java) { AnlzReader.read(anlz(bad)) }

        assertThat(failure.truncated).isFalse()
        assertThat(failure.offset).isEqualTo(28)
    }

    @Test
    fun `a waveform that promises more entries than its section holds is truncated`() {
        val tag = section("PWV5", be { writeInt(2); writeInt(100); writeInt(0) }, ByteArray(10))

        val failure = assertThrows(AnlzFormatException::class.java) { AnlzReader.read(anlz(tag)) }

        assertThat(failure.truncated).isTrue()
    }

    @Test
    fun `a cue list that promises more cues than its section holds is truncated`() {
        val tag = section("PCOB", be { writeInt(0); writeShort(0); writeShort(5); writeInt(0) })

        val failure = assertThrows(AnlzFormatException::class.java) { AnlzReader.read(anlz(tag)) }

        assertThat(failure.truncated).isTrue()
    }

    @Test
    fun `a file longer than its header says is read up to the declared length`() {
        val file = AnlzWriter.writeDat(track)

        val parsed = AnlzReader.read(file + ByteArray(5))

        assertThat(parsed.tags).hasSize(7)
    }

    // ---- builders ---------------------------------------------------------------------------------------------

    private fun be(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()

    /** A section: its fourcc, `len_header` and `len_tag` (12 plus [header] and [body]), [header] and [body]. */
    private fun section(fourCc: String, header: ByteArray, body: ByteArray = ByteArray(0)): ByteArray = be {
        writeBytes(fourCc)
        writeInt(12 + header.size)
        writeInt(12 + header.size + body.size)
        write(header)
        write(body)
    }

    /** A whole file: the 28-byte `PMAI` header followed by [sections]. */
    private fun anlz(vararg sections: ByteArray): ByteArray {
        val body = sections.fold(ByteArray(0)) { a, s -> a + s }
        return be {
            writeBytes("PMAI")
            writeInt(28)
            writeInt(28 + body.size)
            writeInt(1); writeInt(0x0001_0000); writeInt(0x0001_0000); writeInt(0)
            write(body)
        }
    }

    private fun pcpt(hotCue: Int, status: Int, first: Int, last: Int, type: Int, time: Long, loopTime: Long): ByteArray = be {
        writeBytes("PCPT"); writeInt(0x1C); writeInt(0x38)
        writeInt(hotCue); writeInt(status); writeInt(0x0001_0000)
        writeShort(first); writeShort(last)
        writeByte(type); writeByte(0); writeShort(1000)
        writeInt(time.toInt()); writeInt(loopTime.toInt())
        write(ByteArray(16))
    }

    /**
     * A `PCP2` entry. [partial] cuts it right after the loop size (`len_entry` 0x28); a null [color] cuts it after the
     * comment; otherwise it ends with `color_code`, red, green and blue.
     */
    private fun pcp2(
        hotCue: Int,
        type: Int,
        time: Long,
        loopTime: Long = 0xFFFF_FFFFL,
        colorId: Int = 0,
        numerator: Int = 0,
        denominator: Int = 0,
        comment: String? = null,
        color: IntArray? = if (comment == null) intArrayOf(0, 0, 0, 0) else null,
        partial: Boolean = false,
    ): ByteArray {
        val text = if (comment.isNullOrEmpty()) ByteArray(0) else "$comment\u0000".toByteArray(Charsets.UTF_16BE)
        val tail = be {
            writeInt(text.size)
            write(text)
            color?.forEach { writeByte(it) }
        }
        val fixed = be {
            writeInt(hotCue)
            writeByte(type); writeByte(0); writeShort(1000)
            writeInt(time.toInt()); writeInt(loopTime.toInt())
            writeByte(colorId); writeByte(1); write(ByteArray(6))
            writeShort(numerator); writeShort(denominator)
        }
        val body = if (partial) fixed else fixed + tail
        return be {
            writeBytes("PCP2"); writeInt(0x10); writeInt(12 + body.size)
            write(body)
        }
    }
}
