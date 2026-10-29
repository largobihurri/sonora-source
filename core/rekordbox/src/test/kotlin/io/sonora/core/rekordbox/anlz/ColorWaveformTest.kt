package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.rekordbox.DemoTracks
import io.sonora.core.rekordbox.reference.AnlzReader
import io.sonora.core.rekordbox.reference.ColorDetailTag
import io.sonora.core.rekordbox.reference.ColorPreviewTag
import io.sonora.core.rekordbox.reference.ThreeBandWaveformTag
import io.sonora.core.rekordbox.reference.hex
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorWaveformTest {

    private fun planes(low: Int, mid: Int, high: Int, peak: Int, columns: Int = 300) = BandPlanes(
        low = ByteArray(columns) { low.toByte() },
        mid = ByteArray(columns) { mid.toByte() },
        high = ByteArray(columns) { high.toByte() },
        peak = ByteArray(columns) { peak.toByte() },
    )

    private fun read(section: AnlzSection) = AnlzReader.read(AnlzEncoder.encode(listOf(section))).tags.single()

    // ---- rekordbox's own sections -----------------------------------------------------------------------------

    @Test
    fun `PWV5 and PWV4 sections rebuilt from rekordbox's demo export are byte-identical`() {
        for (folder in listOf("P016/0000875E", "P053/0001D21F")) {
            val real = AnlzReader.readRaw(DemoTracks.resource("$folder/ANLZ0000.EXT"))

            val detail = ColorWaveformDetailSection(real.single("PWV5").body())
            val preview = ColorWaveformPreviewSection(real.single("PWV4").body())

            val rebuilt = AnlzReader.readRaw(AnlzEncoder.encode(listOf(detail, preview)))
            assertThat(rebuilt.single("PWV5").bytes).isEqualTo(real.single("PWV5").bytes)
            assertThat(rebuilt.single("PWV4").bytes).isEqualTo(real.single("PWV4").bytes)
        }
    }

    @Test
    fun `the 3-band sections have the headers rekordbox writes in the 2EX file`() {
        val detail = AnlzReader.readRaw(AnlzEncoder.encode(listOf(ThreeBandDetailSection(ByteArray(3 * 44_727))))).single("PWV7")
        val preview = AnlzReader.readRaw(AnlzEncoder.encode(listOf(ThreeBandPreviewSection(ByteArray(3 * 1200))))).single("PWV6")
        val gain = AnlzReader.readRaw(AnlzEncoder.encode(listOf(WaveformGainSection))).single("PWVC")

        // Headers of the PWV7, PWV6 and PWVC of a rekordbox export (the user's library): entry size 3, entry count,
        // 0x00960000 (150 columns a second) for the detail.
        assertThat(detail.bytes.copyOf(24)).isEqualTo(hex("50575637 00000018 00020c3d 00000003 0000aeb7 00960000"))
        assertThat(preview.bytes.copyOf(20)).isEqualTo(hex("50575636 00000014 00000e24 00000003 000004b0"))
        assertThat(gain.bytes.copyOf(14)).isEqualTo(hex("50575643 0000000e 00000014 0000"))
        assertThat(gain.bytes.copyOfRange(14, 20)).isEqualTo(hex("0050 0058 0077"))
    }

    // ---- the colour detail (PWV5) -------------------------------------------------------------------------------

    @Test
    fun `pure bass is red, equal bands are white and the height follows the peak`() {
        val bass = ColorWaveforms.detail(planes(low = 200, mid = 0, high = 0, peak = 255), columns = 3)
        val flat = ColorWaveforms.detail(planes(low = 90, mid = 90, high = 90, peak = 128), columns = 3)

        val bassTag = read(ColorWaveformDetailSection(bass)) as ColorDetailTag
        val flatTag = read(ColorWaveformDetailSection(flat)) as ColorDetailTag

        assertThat(listOf(bassTag.red(0), bassTag.green(0), bassTag.blue(0), bassTag.height(0))).containsExactly(7, 0, 0, 31).inOrder()
        assertThat(listOf(flatTag.red(2), flatTag.green(2), flatTag.blue(2), flatTag.height(2))).containsExactly(7, 7, 7, 16).inOrder()
    }

    @Test
    fun `colours are shares of the loudest band and mids and highs make blue-green`() {
        val tag = read(
            ColorWaveformDetailSection(ColorWaveforms.detail(planes(low = 30, mid = 120, high = 240, peak = 200), columns = 1)),
        ) as ColorDetailTag

        // 30/240 * 7 = 0.9 -> 1, 120/240 * 7 = 3.5 -> 4 (half up), 240/240 * 7 = 7.
        assertThat(listOf(tag.red(0), tag.green(0), tag.blue(0))).containsExactly(1, 4, 7).inOrder()
        assertThat(tag.height(0)).isEqualTo(24) // 200 * 31 / 255 = 24.3
    }

    @Test
    fun `silence is black and flat`() {
        val tag = read(ColorWaveformDetailSection(ColorWaveforms.detail(planes(0, 0, 0, 0), columns = 2))) as ColorDetailTag

        assertThat(tag.entries.toList()).containsExactly(0, 0).inOrder()
    }

    @Test
    fun `the detail has as many columns as asked, padded with the last or cut`() {
        val two = BandPlanes(byteArrayOf(10, 200.toByte()), byteArrayOf(0, 0), byteArrayOf(0, 0), byteArrayOf(40, 255.toByte()))

        val longer = ColorWaveforms.detail(two, columns = 4)
        val shorter = ColorWaveforms.detail(two, columns = 1)

        assertThat(longer).hasLength(8)
        assertThat(longer.copyOfRange(2, 4)).isEqualTo(longer.copyOfRange(6, 8))
        assertThat(longer.copyOfRange(2, 4)).isNotEqualTo(longer.copyOfRange(0, 2))
        assertThat(shorter).hasLength(2)
    }

    // ---- the colour preview (PWV4) ---------------------------------------------------------------------------

    @Test
    fun `the colour preview has 1200 six-byte columns taking the loudest of their stretch`() {
        // 2400 detail columns: column 0 of the preview covers detail 0 and 1, of which the second is the louder.
        val low = ByteArray(2400).also { it[1] = 254.toByte() }
        val peak = ByteArray(2400).also { it[1] = 255.toByte() }
        val planes = BandPlanes(low, ByteArray(2400), ByteArray(2400), peak)

        val tag = read(ColorWaveformPreviewSection(ColorWaveforms.preview(planes))) as ColorPreviewTag

        assertThat(tag.columnCount).isEqualTo(1200)
        assertThat(tag.entryBytes).isEqualTo(6)
        assertThat(tag.channel(0, 2)).isEqualTo(127) // overall level 0..127
        assertThat(tag.channel(0, 3)).isEqualTo(127) // low (254 * 127 / 255 = 126.5, half up)
        assertThat(tag.channel(0, 4)).isEqualTo(0)
        assertThat(tag.channel(0, 5)).isEqualTo(0)
        assertThat(tag.channel(1, 2)).isEqualTo(0)
    }

    @Test
    fun `the two leading preview bytes follow the overall level the way rekordbox's do`() {
        val loud = ColorWaveforms.preview(planes(100, 100, 100, 255, columns = 1200))
        val quiet = ColorWaveforms.preview(planes(0, 0, 0, 0, columns = 1200))

        // Level 127: first byte 127 (sqrt curve), second 128 + 0 / 2. Silence: 0 and 128 + 127 / 2 = 191.
        assertThat(loud.take(6).map { it.toInt() and 0xFF }).containsExactly(127, 128, 127, 50, 50, 50).inOrder()
        assertThat(quiet.take(6).map { it.toInt() and 0xFF }).containsExactly(0, 191, 0, 0, 0, 0).inOrder()
    }

    // ---- the 3-band waveforms ----------------------------------------------------------------------------

    @Test
    fun `the 3-band detail stores low, mid and high in that order and the preview the loudest of each stretch`() {
        val detail = ColorWaveforms.threeBandDetail(planes(low = 10, mid = 20, high = 30, peak = 99, columns = 5), columns = 5)
        val tag = read(ThreeBandDetailSection(detail)) as ThreeBandWaveformTag

        assertThat(tag.isDetail).isTrue()
        assertThat(tag.columnCount).isEqualTo(5)
        assertThat(listOf(tag.low(4), tag.mid(4), tag.high(4))).containsExactly(10, 20, 30).inOrder()

        val low = ByteArray(2400).also { it[2399] = 77 }
        val preview = ColorWaveforms.threeBandPreview(BandPlanes(low, ByteArray(2400), ByteArray(2400) { 5 }, ByteArray(2400)))
        val previewTag = read(ThreeBandPreviewSection(preview)) as ThreeBandWaveformTag
        assertThat(previewTag.isDetail).isFalse()
        assertThat(previewTag.columnCount).isEqualTo(1200)
        assertThat(listOf(previewTag.low(1199), previewTag.mid(1199), previewTag.high(1199))).containsExactly(77, 0, 5).inOrder()
        assertThat(previewTag.low(0)).isEqualTo(0)
    }

    @Test
    fun `sections check their sizes`() {
        assertThrows(IllegalArgumentException::class.java) { ColorWaveformPreviewSection(ByteArray(7199)) }
        assertThrows(IllegalArgumentException::class.java) { ColorWaveformDetailSection(ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { ThreeBandPreviewSection(ByteArray(3599)) }
        assertThrows(IllegalArgumentException::class.java) { ThreeBandDetailSection(ByteArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { BandPlanes(ByteArray(3), ByteArray(3), ByteArray(3), ByteArray(2)) }
    }

    // ---- the writer -------------------------------------------------------------------------------------------

    private fun track(bands: BandPlanes?) = AnlzTrack(
        filePath = "/Contents/A/B/t.aiff",
        beatGrid = BeatGrid(120.0, 0.0, 1, 4),
        waveform = BlueWaveform(ByteArray(300), ByteArray(BlueWaveform.PREVIEW_COLUMNS), ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS)),
        bands = bands,
    )

    @Test
    fun `ext carries the colour waveforms after the cue lists and a 2EX file holds the 3-band ones`() {
        val bands = planes(low = 50, mid = 100, high = 150, peak = 200, columns = 300)

        val ext = AnlzReader.read(AnlzWriter.writeExt(track(bands)))
        val ex2 = AnlzReader.read(checkNotNull(AnlzWriter.writeEx2(track(bands))))

        assertThat(ext.tags.map { it.fourCc })
            .containsExactly("PPTH", "PWV3", "PCOB", "PCOB", "PCO2", "PCO2", "PWV5", "PWV4").inOrder()
        assertThat((ext.single("PWV5") as ColorDetailTag).entries.size).isEqualTo(300) // as many as the blue detail
        assertThat(ex2.tags.map { it.fourCc }).containsExactly("PPTH", "PWV7", "PWV6", "PWVC").inOrder()
        assertThat((ex2.single("PWV7") as ThreeBandWaveformTag).columnCount).isEqualTo(300)
        assertThat(ex2.path).isEqualTo("/Contents/A/B/t.aiff")
    }

    @Test
    fun `without colour data nothing changes and there is no 2EX file`() {
        val plain = AnlzReader.read(AnlzWriter.writeExt(track(null)))

        assertThat(plain.tags.map { it.fourCc }).containsExactly("PPTH", "PWV3", "PCOB", "PCOB", "PCO2", "PCO2").inOrder()
        assertThat(AnlzWriter.writeEx2(track(null))).isNull()
    }
}
