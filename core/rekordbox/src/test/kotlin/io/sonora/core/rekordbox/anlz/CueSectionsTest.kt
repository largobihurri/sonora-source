package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.AnlzReader
import io.sonora.core.rekordbox.reference.Cue
import io.sonora.core.rekordbox.reference.CueListTag
import io.sonora.core.rekordbox.reference.ExtendedCue
import io.sonora.core.rekordbox.reference.ExtendedCueListTag
import io.sonora.core.rekordbox.reference.hex
import org.junit.Assert.assertThrows
import org.junit.Test

/** `PCOB` and `PCO2` with entries, read back with the independent reader and pinned to bytes rekordbox wrote. */
class CueSectionsTest {

    private fun read(section: AnlzSection) = AnlzReader.read(AnlzEncoder.encode(listOf(section))).tags.single()

    // ---- PCOB -----------------------------------------------------------------------------------------------

    @Test
    fun `a hot cue is a 56-byte PCPT exactly as rekordbox writes it`() {
        // The PCPT of hot cue A at 33108 ms in an export made by rekordbox (the user's own library).
        val real = hex(
            "50435054 0000001c 00000038 00000001 00000000 00010000 ffffffff 01 0003e8 00008154 ffffffff" +
                "00000000 00000000 00000000 00000000",
        )

        val section = CueListSection(CueListType.HOT, listOf(AnlzCue(hotCue = 1, timeMs = 33_108)))

        val bytes = AnlzEncoder.encode(listOf(section))
        val header = hex("50434f42 00000018 00000050 00000001 0000 0001 ffffffff")
        assertThat(bytes.copyOfRange(28, bytes.size)).isEqualTo(header + real)
    }

    @Test
    fun `hot cues and loops round-trip through the reader`() {
        val section = CueListSection(
            CueListType.HOT,
            listOf(
                AnlzCue(hotCue = 1, timeMs = 1_500),
                AnlzCue(hotCue = 3, timeMs = 8_000, loopEndMs = 12_000),
            ),
        )

        val tag = read(section) as CueListTag

        assertThat(tag.type).isEqualTo(CueListType.HOT)
        assertThat(tag.memoryCount).isEqualTo(0xFFFF_FFFFL)
        assertThat(tag.cues).containsExactly(
            Cue(hotCue = 1, status = 0, orderFirst = 0xFFFF, orderLast = 0xFFFF, typeCode = 1, timeMs = 1_500, loopTimeMs = null),
            Cue(hotCue = 3, status = 0, orderFirst = 0xFFFF, orderLast = 0xFFFF, typeCode = 2, timeMs = 8_000, loopTimeMs = 12_000),
        ).inOrder()
    }

    @Test
    fun `memory cues are listed in time order, linked by their sort keys`() {
        val section = CueListSection(
            CueListType.MEMORY,
            listOf(AnlzCue(0, 9_000), AnlzCue(0, 1_000), AnlzCue(0, 5_000)),
        )

        val tag = read(section) as CueListTag

        assertThat(tag.type).isEqualTo(CueListType.MEMORY)
        assertThat(tag.cues.map { it.timeMs }).containsExactly(1_000L, 5_000L, 9_000L).inOrder()
        // First cue: no previous (ffff), next 1; then 0 and 2; the last has no next (ffff).
        assertThat(tag.cues.map { it.orderFirst to it.orderLast })
            .containsExactly(0xFFFF to 1, 0 to 2, 1 to 0xFFFF).inOrder()
        assertThat(tag.cues.map { it.hotCue }).containsExactly(0, 0, 0)
    }

    @Test
    fun `one memory cue has no neighbours`() {
        val tag = read(CueListSection(CueListType.MEMORY, listOf(AnlzCue(0, 777)))) as CueListTag

        assertThat(tag.cues.single().let { it.orderFirst to it.orderLast }).isEqualTo(0xFFFF to 0xFFFF)
    }

    @Test
    fun `the old cue list refuses hot cues past C and the wrong kind of cue`() {
        assertThrows(IllegalArgumentException::class.java) { CueListSection(CueListType.HOT, listOf(AnlzCue(4, 10))) }
        assertThrows(IllegalArgumentException::class.java) { CueListSection(CueListType.HOT, listOf(AnlzCue(0, 10))) }
        assertThrows(IllegalArgumentException::class.java) { CueListSection(CueListType.MEMORY, listOf(AnlzCue(1, 10))) }
    }

    // ---- PCO2 -----------------------------------------------------------------------------------------------

    @Test
    fun `a hot cue without a comment is the 88-byte PCP2 rekordbox writes`() {
        // Hot cue D at 198626 ms in rekordbox's own export, colour code 9 with its RGB 00e0ff.
        val real = hex(
            "50435032 00000010 00000058 00000004 01 0003e8 000307e2 ffffffff 00 01000000000000 0000 0000" +
                "00000000 09 00 e0 ff" + "00".repeat(40),
        )

        val section = ExtendedCueListSection(
            CueListType.HOT,
            listOf(AnlzCue(hotCue = 4, timeMs = 198_626, rgb = 0x00E0FF)),
        )

        val bytes = AnlzEncoder.encode(listOf(section))
        assertThat(bytes.copyOfRange(28, bytes.size)).isEqualTo(hex("50434f32 00000014 0000006c 00000001 0001 0000") + real)
    }

    @Test
    fun `a loop with its size is the PCP2 of rekordbox's 32-beat hot cue loop`() {
        // A hot cue loop (hot cue A, 0x5635 to 0x8150 ms, 32 beats, colour code 0x20 = ffe800) of rekordbox's export.
        val real = hex(
            "50435032 00000010 00000058 00000001 02 0003e8 00005635 00008150 00 01000000000000 0020 0001" +
                "00000000 20 ff e8 00" + "00".repeat(40),
        )

        val section = ExtendedCueListSection(
            CueListType.HOT,
            listOf(AnlzCue(1, 0x5635, loopEndMs = 0x8150, rgb = 0xFFE800, colorCode = 0x20, loopSize = LoopSize(32, 1))),
        )

        val bytes = AnlzEncoder.encode(listOf(section))
        assertThat(bytes.copyOfRange(28 + 20, bytes.size)).isEqualTo(real)
    }

    @Test
    fun `colours and comments round-trip through the reader`() {
        val section = ExtendedCueListSection(
            CueListType.HOT,
            listOf(
                AnlzCue(hotCue = 2, timeMs = 30_250, rgb = 0x28E214, comment = "Drop"),
                AnlzCue(hotCue = 8, timeMs = 64_000, loopEndMs = 66_000, rgb = 0xFF3B30, loopSize = LoopSize(1, 2)),
            ),
        )

        val tag = read(section) as ExtendedCueListTag

        assertThat(tag.type).isEqualTo(CueListType.HOT)
        assertThat(tag.cues[0]).isEqualTo(
            ExtendedCue(2, 1, 30_250, null, 0, 0, 0, "Drop", colorCode = 0x16, red = 0x28, green = 0xE2, blue = 0x14),
        )
        assertThat(tag.cues[1].hotCue).isEqualTo(8)
        assertThat(tag.cues[1].isLoop).isTrue()
        assertThat(tag.cues[1].loopTimeMs).isEqualTo(66_000)
        assertThat(tag.cues[1].loopNumerator to tag.cues[1].loopDenominator).isEqualTo(1 to 2)
        assertThat(tag.cues[1].red).isEqualTo(0xFF)
    }

    @Test
    fun `non-ascii comments are utf-16 with a terminator and the entry length follows`() {
        val section = ExtendedCueListSection(CueListType.HOT, listOf(AnlzCue(1, 10, rgb = 0x305AFF, comment = "Ñandú")))

        val tag = read(section) as ExtendedCueListTag
        val raw = AnlzReader.readRaw(AnlzEncoder.encode(listOf(section))).single("PCO2")

        assertThat(tag.cues.single().comment).isEqualTo("Ñandú")
        // 0x2c fixed, 12 comment bytes ("Ñandú" 5 units + terminator), 4 colour bytes, 40 padding.
        assertThat(raw.u32(0x14 + 8)).isEqualTo(0x2C + 12 + 4 + 40L)
    }

    @Test
    fun `memory cues carry the colour id of the nearest rekordbox memory colour`() {
        val section = ExtendedCueListSection(
            CueListType.MEMORY,
            listOf(AnlzCue(0, 1_000, rgb = 0xFF3B30), AnlzCue(0, 2_000, rgb = null)),
        )

        val cues = (read(section) as ExtendedCueListTag).cues

        assertThat(cues.map { it.colorId }).containsExactly(CueColors.memoryColorId(0xFF3B30), 0).inOrder()
        assertThat(cues[0].colorId).isEqualTo(2) // Red
        assertThat(cues[0].red).isEqualTo(0xFF)
        assertThat(cues[1].colorCode).isEqualTo(0)
    }

    @Test
    fun `the extended list takes all eight pads and refuses a mismatched kind`() {
        val cues = (1..8).map { AnlzCue(it, it * 1_000L, rgb = 0x305AFF) }

        val tag = read(ExtendedCueListSection(CueListType.HOT, cues)) as ExtendedCueListTag

        assertThat(tag.cues.map { it.hotCue }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThrows(IllegalArgumentException::class.java) { ExtendedCueListSection(CueListType.HOT, listOf(AnlzCue(0, 1))) }
        assertThrows(IllegalArgumentException::class.java) { ExtendedCueListSection(CueListType.MEMORY, listOf(AnlzCue(2, 1))) }
    }

    @Test
    fun `cue values are validated`() {
        assertThrows(IllegalArgumentException::class.java) { AnlzCue(9, 1) }
        assertThrows(IllegalArgumentException::class.java) { AnlzCue(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { AnlzCue(1, 0xFFFF_FFFFL) }
        assertThrows(IllegalArgumentException::class.java) { AnlzCue(1, 100, loopEndMs = 100) }
        assertThrows(IllegalArgumentException::class.java) { AnlzCue(1, 100, rgb = 0x1000000) }
        assertThrows(IllegalArgumentException::class.java) { LoopSize(3, 1) }
        assertThrows(IllegalArgumentException::class.java) { LoopSize(2, 2) }
    }

    // ---- the writer ---------------------------------------------------------------------------------------

    @Test
    fun `the writer lists A to C in the old lists and every pad in the extended ones`() {
        val cues = (1..8).map { AnlzCue(it, it * 1_000L, rgb = CuePaletteForTests.rgb(it)) } +
            listOf(AnlzCue(0, 500, rgb = 0xFF3B30), AnlzCue(0, 40_000))

        val dat = AnlzReader.read(AnlzWriter.writeDat(trackWith(cues)))
        val ext = AnlzReader.read(AnlzWriter.writeExt(trackWith(cues)))

        val datLists = dat.filter<CueListTag>()
        assertThat(datLists.map { it.type }).containsExactly(CueListType.HOT, CueListType.MEMORY).inOrder()
        assertThat(datLists[0].cues.map { it.hotCue }).containsExactly(1, 2, 3).inOrder()
        assertThat(datLists[1].cues.map { it.timeMs }).containsExactly(500L, 40_000L).inOrder()
        assertThat(ext.all("PCOB").map { (it as CueListTag).cues.size }).containsExactly(3, 2).inOrder()
        val extended = ext.filter<ExtendedCueListTag>()
        assertThat(extended.map { it.type }).containsExactly(CueListType.HOT, CueListType.MEMORY).inOrder()
        assertThat(extended[0].cues.map { it.hotCue }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(extended[1].cues.map { it.timeMs }).containsExactly(500L, 40_000L).inOrder()
    }

    @Test
    fun `a track without cues writes the same empty lists as before`() {
        val plain = trackWith(emptyList())

        assertThat(AnlzWriter.writeDat(plain)).isEqualTo(AnlzWriter.writeDat(plain.copy(cues = emptyList())))
        val ext = AnlzReader.read(AnlzWriter.writeExt(plain))
        assertThat(ext.all("PCOB").map { (it as CueListTag).cues }).containsExactly(emptyList<Cue>(), emptyList<Cue>())
        assertThat(ext.all("PCO2").map { (it as ExtendedCueListTag).cues }).containsExactly(emptyList<ExtendedCue>(), emptyList<ExtendedCue>())
    }

    private fun trackWith(cues: List<AnlzCue>) = AnlzTrack(
        filePath = "/Contents/A/B/t.aiff",
        beatGrid = io.sonora.core.domain.analysis.BeatGrid(120.0, 0.0, 1, 4),
        waveform = io.sonora.core.domain.analysis.BlueWaveform(
            ByteArray(5),
            ByteArray(io.sonora.core.domain.analysis.BlueWaveform.PREVIEW_COLUMNS),
            ByteArray(io.sonora.core.domain.analysis.BlueWaveform.TINY_PREVIEW_COLUMNS),
        ),
        cues = cues,
    )
}

private object CuePaletteForTests {
    private val rgbs = intArrayOf(0x28E214, 0xFF3B30, 0xFF9500, 0xFFD60A, 0x30C8E8, 0x305AFF, 0xB04CFF, 0xFF4FA3)

    fun rgb(hotCue: Int): Int = rgbs[hotCue - 1]
}
