package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.rekordbox.reference.AnlzReader
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Writes a synthetic track's analysis files under `build/anlz-out/` (stick layout), so they can be checked by hand
 * with the rekordcrate and pyrekordbox readers (`rekordcrate dump-anlz <file>`); here only re-read with the test reader.
 */
class AnlzOracleFilesTest {

    /** Holds the files when the build did not set `sonora.anlzOutDir` (e.g. an IDE run); deleted after the test. */
    @get:Rule
    val scratch = TemporaryFolder()

    private val outDir: File by lazy { System.getProperty("sonora.anlzOutDir")?.let(::File) ?: scratch.root }

    @Test
    fun `writes a 30 second 128 bpm track in the stick layout`() {
        val filePath = "/Contents/Sonora/Oracle/Click 128.aiff"
        val seconds = 30
        val detail = ByteArray(seconds * BlueWaveform.DETAIL_COLUMNS_PER_SECOND) {
            BlueWaveform.packColumn(height = 8 + it % 24, whiteness = it / 150 % 8)
        }
        val preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { BlueWaveform.packColumn(it % 32, it / 50 % 8) }
        val tiny = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() }
        val track = AnlzTrack(
            filePath,
            BeatGrid.spanning(bpm = 128.0, anchorMs = 25.0, anchorBeatInBar = 1, durationMs = seconds * 1000.0),
            BlueWaveform(detail, preview, tiny),
        )
        val paths = AnlzPaths.forAudioPath(filePath)

        val dat = File(outDir, paths.datPath).apply { parentFile.mkdirs(); writeBytes(AnlzWriter.writeDat(track)) }
        val ext = File(outDir, paths.extPath).apply { writeBytes(AnlzWriter.writeExt(track)) }

        val readDat = AnlzReader.readRaw(dat.readBytes())
        assertThat(AnlzReader.path(readDat.single("PPTH"))).isEqualTo(filePath)
        assertThat(AnlzReader.beats(readDat.single("PQTZ"))).hasSize(track.beatGrid.beatCount)
        assertThat(AnlzReader.readRaw(ext.readBytes()).single("PWV3").body()).isEqualTo(detail)
    }

    @Test
    fun `writes a track with cues, loops and colour waveforms in the stick layout`() {
        val filePath = "/Contents/Sonora/Oracle/Cues 128.aiff"
        val seconds = 30
        val columns = seconds * BlueWaveform.DETAIL_COLUMNS_PER_SECOND
        val track = AnlzTrack(
            filePath,
            BeatGrid.spanning(bpm = 128.0, anchorMs = 25.0, anchorBeatInBar = 1, durationMs = seconds * 1000.0),
            BlueWaveform(
                ByteArray(columns) { BlueWaveform.packColumn(8 + it % 24, it / 150 % 8) },
                ByteArray(BlueWaveform.PREVIEW_COLUMNS) { BlueWaveform.packColumn(it % 32, it / 50 % 8) },
                ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
            ),
            cues = (1..8).map { AnlzCue(it, it * 3_000L, rgb = CueColors.hotCueRgb(it * 7), comment = "Cue $it") } +
                listOf(
                    AnlzCue(0, 500, rgb = 0xFF3B30),
                    AnlzCue(0, 12_000),
                    AnlzCue(2, 20_000, loopEndMs = 24_687, rgb = 0x305AFF, loopSize = LoopSize(8, 1)),
                ),
            bands = BandPlanes(
                ByteArray(columns) { (it % 200).toByte() }, ByteArray(columns) { (it % 120).toByte() },
                ByteArray(columns) { (it % 60).toByte() }, ByteArray(columns) { (40 + it % 200).toByte() },
            ),
        )
        val paths = AnlzPaths.forAudioPath(filePath)

        File(outDir, paths.datPath).apply { parentFile.mkdirs(); writeBytes(AnlzWriter.writeDat(track)) }
        File(outDir, paths.extPath).writeBytes(AnlzWriter.writeExt(track))
        val ex2 = File(outDir, paths.ex2Path).apply { writeBytes(checkNotNull(AnlzWriter.writeEx2(track))) }

        assertThat(AnlzReader.readRaw(File(outDir, paths.extPath).readBytes()).sections.map { it.fourCc })
            .containsExactly("PPTH", "PWV3", "PCOB", "PCOB", "PCO2", "PCO2", "PWV5", "PWV4").inOrder()
        assertThat(AnlzReader.readRaw(ex2.readBytes()).sections.map { it.fourCc })
            .containsExactly("PPTH", "PWV7", "PWV6", "PWVC").inOrder()
    }
}
