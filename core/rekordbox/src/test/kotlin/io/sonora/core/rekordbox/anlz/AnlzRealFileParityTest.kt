package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.rekordbox.DemoTracks
import io.sonora.core.rekordbox.reference.AnlzReader
import org.junit.Test

/**
 * Rebuilds rekordbox's own analysis files of its demo tracks (copied from rekordcrate, see the README next to them)
 * from the values they hold, and checks the result byte for byte against the originals.
 */
class AnlzRealFileParityTest {

    private val tracks = listOf("P016/0000875E", "P053/0001D21F")

    @Test
    fun `dat rebuilt from the real values is byte-identical apart from the mp3 vbr index`() {
        for (folder in tracks) {
            val real = DemoTracks.resource("$folder/ANLZ0000.DAT")
            val parsed = AnlzReader.readRaw(real)

            val ours = AnlzEncoder.encode(
                listOf(
                    PathSection(AnlzReader.path(parsed.single("PPTH"))),
                    VbrSection,
                    BeatGridSection(AnlzReader.beats(parsed.single("PQTZ"))),
                    WaveformPreviewSection(parsed.single("PWAV").body()),
                    TinyWaveformPreviewSection(parsed.single("PWV2").body()),
                    CueListSection(CueListType.HOT),
                    CueListSection(CueListType.MEMORY),
                ),
            )

            // The demo tracks are MP3s, whose PVBR holds a seek index; for AIFF rekordbox leaves it all zeros.
            val expected = real.copyOf()
            val pvbrAt = real.indexOf(parsed.single("PVBR").bytes)
            expected.fill(0, pvbrAt + 12, pvbrAt + 1620)
            assertThat(ours).isEqualTo(expected)
        }
    }

    @Test
    fun `ext rebuilt from the real values matches the real sections up to the colour data`() {
        for (folder in tracks) {
            val real = AnlzReader.readRaw(DemoTracks.resource("$folder/ANLZ0000.EXT"))

            val ours = AnlzReader.readRaw(
                AnlzEncoder.encode(
                    listOf(
                        PathSection(AnlzReader.path(real.single("PPTH"))),
                        WaveformDetailSection(real.single("PWV3").body()),
                        CueListSection(CueListType.HOT),
                        CueListSection(CueListType.MEMORY),
                        ExtendedCueListSection(CueListType.HOT),
                        ExtendedCueListSection(CueListType.MEMORY),
                    ),
                ),
            )

            // rekordbox goes on with PQT2, PWV5, PWV4 and PSSI, which the blue-waveform players do not read.
            val realPrefix = real.sections.take(6)
            assertThat(ours.sections.map { it.fourCc }).isEqualTo(realPrefix.map { it.fourCc })
            ours.sections.zip(realPrefix).forEach { (mine, theirs) -> assertThat(mine.bytes).isEqualTo(theirs.bytes) }
            assertThat(ours.header.copyOf(8)).isEqualTo(real.header.copyOf(8))
            assertThat(ours.header.copyOfRange(12, 28)).isEqualTo(real.header.copyOfRange(12, 28))
        }
    }

    @Test
    fun `the writer lays the real waveforms and path out exactly like rekordbox`() {
        for (folder in tracks) {
            val dat = AnlzReader.readRaw(DemoTracks.resource("$folder/ANLZ0000.DAT"))
            val ext = AnlzReader.readRaw(DemoTracks.resource("$folder/ANLZ0000.EXT"))
            val realBeats = AnlzReader.beats(dat.single("PQTZ"))
            val first = realBeats.first()
            val track = AnlzTrack(
                filePath = AnlzReader.path(dat.single("PPTH")),
                // rekordbox rounds its beat times from its own internal grid, so only the grid shape is rebuilt.
                beatGrid = BeatGrid(first.tempo / 100.0, first.timeMs.toDouble(), first.beatInBar, realBeats.size),
                waveform = BlueWaveform(
                    detail = ext.single("PWV3").body(),
                    preview = dat.single("PWAV").body(),
                    tinyPreview = dat.single("PWV2").body(),
                ),
            )

            val ourDat = AnlzReader.readRaw(AnlzWriter.writeDat(track))
            val ourExt = AnlzReader.readRaw(AnlzWriter.writeExt(track))

            assertThat(ourDat.sections.map { it.fourCc }).isEqualTo(dat.sections.map { it.fourCc })
            for ((mine, theirs) in ourDat.sections.zip(dat.sections)) {
                when (mine.fourCc) {
                    "PVBR" -> assertThat(mine.bytes.copyOf(12)).isEqualTo(theirs.bytes.copyOf(12))
                    "PQTZ" -> {
                        assertThat(mine.bytes.copyOf(mine.lenHeader)).isEqualTo(theirs.bytes.copyOf(theirs.lenHeader))
                        val ourBeats = AnlzReader.beats(mine)
                        assertThat(ourBeats.map { it.beatInBar to it.tempo })
                            .isEqualTo(realBeats.map { it.beatInBar to it.tempo })
                        ourBeats.zip(realBeats).forEach { (a, b) -> assertThat(a.timeMs).isWithin(1).of(b.timeMs) }
                    }
                    else -> assertThat(mine.bytes).isEqualTo(theirs.bytes)
                }
            }
            assertThat(ourDat.header).isEqualTo(dat.header)
            ourExt.sections.zip(ext.sections.take(6)).forEach { (mine, theirs) ->
                assertThat(mine.bytes).isEqualTo(theirs.bytes)
            }
        }
    }
}

private fun ByteArray.indexOf(part: ByteArray): Int =
    (0..size - part.size).first { start -> part.indices.all { this[start + it] == part[it] } }
