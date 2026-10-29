package io.sonora.tools.calibration.runner

import io.sonora.core.rekordbox.reference.AnlzFile
import io.sonora.core.rekordbox.reference.AnlzReader
import io.sonora.core.rekordbox.reference.BeatGridTag
import io.sonora.core.rekordbox.reference.ColorDetailTag
import io.sonora.core.rekordbox.reference.ColorPreviewTag
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.reference.PdbTrack
import io.sonora.core.rekordbox.reference.ThreeBandWaveformTag
import io.sonora.core.rekordbox.reference.WaveformDetailTag
import java.nio.file.Files
import java.nio.file.Path

/**
 * The user's rekordbox reference export, read only: `PIONEER/rekordbox/export.pdb` for the track rows (path, tempo,
 * key), `PIONEER/USBANLZ/.../ANLZ0000.DAT|EXT|2EX` for the beat grid and the waveforms, and the `SELECTION.txt` list
 * of the audio files copied next to them (`Contents/...`, one per line). Nothing is written under [root].
 */
class ReferenceLibrary(val root: Path) {

    private val pdb = PdbReader.read(root.resolve(EXPORT_PDB))

    /** Every track row of `export.pdb`. */
    val tracks: List<PdbTrack> = pdb.tracks

    /** Key names of `export.pdb` by key id. */
    val keyNames: Map<Int, String> = pdb.keyNames

    /** The lines of `SELECTION.txt` (the audio files present), or an empty list without one. */
    fun selection(): List<String> {
        val file = root.resolve(SELECTION)
        return if (Files.isRegularFile(file)) Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    /** The audio file of [track]. */
    fun audioFile(track: PdbTrack): Path = ReferencePaths.resolve(root, track.filePath)

    /** The `PQTZ` grid of [track] alone (cheap: the `.DAT` only), for choosing a subset by tempo. */
    fun grid(track: PdbTrack): ReferenceGrid {
        val dat = AnlzReader.read(ReferencePaths.anlzSet(root, track.analyzePath).dat)
        return ReferenceGrid.fromAnlz(dat.first<BeatGridTag>()?.beats ?: emptyList())
    }

    /**
     * Everything rekordbox stored for [track] (listed as [selectionPath]). The `.DAT`'s `PPTH` must name the same file
     * as the row, else the export is inconsistent and this throws.
     */
    fun load(selectionPath: String, track: PdbTrack): ReferenceTrack {
        val files = ReferencePaths.anlzSet(root, track.analyzePath)
        val dat = AnlzReader.read(files.dat)
        val ppth = dat.path
        check(ppth == null || ReferencePaths.key(ppth) == ReferencePaths.key(track.filePath)) {
            "PPTH of ${files.dat} is $ppth, the export row says ${track.filePath}"
        }
        val ext = readIfPresent(files.ext)
        val twoEx = readIfPresent(files.twoEx)
        val keyName = keyNames[track.keyId]
        return ReferenceTrack(
            path = selectionPath,
            format = ReferencePaths.format(track.filePath),
            pdbBpm = track.tempoBpm,
            grid = ReferenceGrid.fromAnlz(dat.first<BeatGridTag>()?.beats ?: emptyList()),
            keyName = keyName,
            key = ReferenceKeys.parse(keyName),
            threeBandDetail = twoEx?.filter<ThreeBandWaveformTag>()?.firstOrNull { it.isDetail }?.let(::planes),
            threeBandPreview = twoEx?.filter<ThreeBandWaveformTag>()?.firstOrNull { !it.isDetail }?.let(::planes),
            colorDetailHeight = ext?.first<ColorDetailTag>()?.let { tag -> ByteArray(tag.entries.size) { tag.height(it).toByte() } },
            monoDetailHeight = ext?.first<WaveformDetailTag>()?.let { tag -> ByteArray(tag.columns.size) { tag.height(it).toByte() } },
            colorPreview = ext?.first<ColorPreviewTag>()?.let { tag ->
                List(tag.entryBytes) { channel -> ByteArray(tag.columnCount) { tag.channel(it, channel).toByte() } }
            },
        )
    }

    /** The 3-band planes of a `PWV6`/`PWV7` tag in file order: byte 0, 1 and 2 of every column. */
    private fun planes(tag: ThreeBandWaveformTag): List<ByteArray> =
        List(THREE_BANDS) { b -> ByteArray(tag.columnCount) { tag.data[it * tag.entryBytes + b] } }

    private fun readIfPresent(file: Path): AnlzFile? = if (Files.isRegularFile(file)) AnlzReader.read(file) else null

    companion object {
        const val EXPORT_PDB: String = "PIONEER/rekordbox/export.pdb"
        const val SELECTION: String = "SELECTION.txt"
        private const val THREE_BANDS = 3
    }
}
