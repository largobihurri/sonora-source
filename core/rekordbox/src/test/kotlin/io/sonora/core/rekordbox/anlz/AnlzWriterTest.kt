package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.rekordbox.reference.AnlzReader
import io.sonora.core.rekordbox.reference.hex
import org.junit.Assert.assertThrows
import org.junit.Test

class AnlzWriterTest {

    private val path = "/Contents/A/B/t.aiff"
    private val pathBytes = hex(path.map { "00%02x".format(it.code) }.joinToString("") + "0000")
    private val detail = hex("e0 1f 00 ff 25")
    private val preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { it.toByte() }
    private val tiny = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() }

    private fun track(grid: BeatGrid = BeatGrid(120.0, 25.0, 3, 5)) =
        AnlzTrack(path, grid, BlueWaveform(detail, preview, tiny))

    private fun pmai(fileLength: Int) =
        hex("504d4149 0000001c %08x 00000001 00010000 00010000 00000000".format(fileLength))

    private val ppth = hex("50505448 00000010 0000003a 0000002a") + pathBytes
    private val hotCues = hex("50434f42 00000018 00000018 00000001 0000 0000 ffffffff")
    private val memoryCues = hex("50434f42 00000018 00000018 00000000 0000 0000 ffffffff")

    @Test
    fun `dat holds the blue-waveform player sections in rekordbox order with exact bytes`() {
        val dat = AnlzWriter.writeDat(track())

        val expected = pmai(2358) +
            ppth +
            hex("50564252 00000010 00000654") + ByteArray(1608) +
            hex(
                "5051545a 00000018 00000040 00000000 00080000 00000005" +
                    "0003 2ee0 00000019 0004 2ee0 0000020d 0001 2ee0 00000401 0002 2ee0 000005f5 0003 2ee0 000007e9",
            ) +
            hex("50574156 00000014 000001a4 00000190 00010000") + preview +
            hex("50575632 00000014 00000078 00000064 00010000") + tiny +
            hotCues + memoryCues
        assertThat(dat).isEqualTo(expected)
        assertThat(AnlzReader.readRaw(dat).sections.map { it.fourCc })
            .containsExactly("PPTH", "PVBR", "PQTZ", "PWAV", "PWV2", "PCOB", "PCOB").inOrder()
    }

    @Test
    fun `ext holds the path, the blue detail and empty cue lists with exact bytes`() {
        val ext = AnlzWriter.writeExt(track())

        val expected = pmai(203) +
            ppth +
            hex("50575633 00000018 0000001d 00000001 00000005 00960000") + detail +
            hotCues + memoryCues +
            hex("50434f32 00000014 00000014 00000001 0000 0000") +
            hex("50434f32 00000014 00000014 00000000 0000 0000")
        assertThat(ext).isEqualTo(expected)
    }

    @Test
    fun `beat times are rounded to the nearest millisecond`() {
        val beats = BeatGridSection.from(BeatGrid(128.0, 25.0, 1, 4)).beats

        assertThat(beats).containsExactly(
            AnlzBeat(1, 12800, 25),
            AnlzBeat(2, 12800, 494), // 493.75
            AnlzBeat(3, 12800, 963), // 962.5
            AnlzBeat(4, 12800, 1431), // 1431.25
        ).inOrder()
    }

    @Test
    fun `explicit beats replace the constant grid, so a track can change tempo`() {
        val beats = listOf(AnlzBeat(3, 12800, 40), AnlzBeat(4, 12800, 509), AnlzBeat(1, 13000, 971), AnlzBeat(2, 13000, 1433))

        val dat = AnlzReader.read(AnlzWriter.writeDat(track().copy(beats = beats)))

        assertThat((dat.single("PQTZ") as io.sonora.core.rekordbox.reference.BeatGridTag).beats).isEqualTo(beats)
        assertThat(AnlzWriter.writeDat(track().copy(beats = emptyList())))
            .isEqualTo(AnlzWriter.writeDat(track(BeatGrid(120.0, 0.0, 1, 0))))
    }

    @Test
    fun `tempo is stored in hundredths of a bpm`() {
        val beats = BeatGridSection.from(BeatGrid(123.456, 0.0, 1, 1)).beats

        assertThat(beats.single().tempo).isEqualTo(12346)
    }

    @Test
    fun `an empty grid writes a beat grid section without beats`() {
        val pqtz = AnlzReader.readRaw(AnlzWriter.writeDat(track(BeatGrid(120.0, 0.0, 1, 0)))).single("PQTZ")

        assertThat(pqtz.bytes).isEqualTo(hex("5051545a 00000018 00000018 00000000 00080000 00000000"))
    }

    @Test
    fun `non-ascii paths are stored as utf-16 big-endian with a terminating nul`() {
        val ppth = AnlzReader.readRaw(AnlzEncoder.encode(listOf(PathSection("/Contents/Björk/日本.aiff")))).single("PPTH")

        assertThat(ppth.u32(12)).isEqualTo(ppth.body().size.toLong())
        assertThat(ppth.body()).isEqualTo("/Contents/Björk/日本.aiff\u0000".toByteArray(Charsets.UTF_16BE))
    }

    @Test
    fun `file length counts the header and every section`() {
        val file = AnlzEncoder.encode(listOf(VbrSection, CueListSection(CueListType.HOT)))

        assertThat(file.size).isEqualTo(28 + 1620 + 24)
        assertThat(AnlzReader.readRaw(file).header).isEqualTo(pmai(28 + 1620 + 24))
    }

    @Test
    fun `an empty section list is just the file header`() {
        assertThat(AnlzEncoder.encode(emptyList())).isEqualTo(pmai(28))
    }

    @Test
    fun `a tempo that rounds to zero hundredths is rejected, also without beats`() {
        for (beats in listOf(0, 1)) {
            assertThrows(IllegalArgumentException::class.java) { BeatGridSection.from(BeatGrid(0.004, 0.0, 1, beats)) }
        }
        assertThat(BeatGridSection.from(BeatGrid(0.01, 0.0, 1, 1)).beats.single().tempo).isEqualTo(1)
    }

    @Test
    fun `invalid input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PathSection("Contents/a.aiff") }
        assertThrows(IllegalArgumentException::class.java) { BeatGridSection.from(BeatGrid(700.0, 0.0, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { AnlzBeat(5, 12000, 0) }
        assertThrows(IllegalArgumentException::class.java) { AnlzBeat(1, 12000, -1) }
        assertThrows(IllegalArgumentException::class.java) { WaveformPreviewSection(ByteArray(399)) }
        assertThrows(IllegalArgumentException::class.java) { TinyWaveformPreviewSection(ByteArray(101)) }
    }
}
