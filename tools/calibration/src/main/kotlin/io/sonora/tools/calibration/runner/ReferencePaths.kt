package io.sonora.tools.calibration.runner

import io.sonora.core.rekordbox.reference.PdbTrack
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/** The three analysis files rekordbox writes for one track: beat grid and cues (`.DAT`), colour waveforms and phrases (`.EXT`), 3-band waveforms (`.2EX`). */
data class AnlzFileSet(val dat: Path, val ext: Path, val twoEx: Path)

/** Selection entries paired with their `export.pdb` rows (in selection order), and the entries no row has. */
class SelectionMatch(val matched: List<Pair<String, PdbTrack>>, val unmatched: List<String>)

/**
 * Maps the paths of a rekordbox USB export to files on disk. `export.pdb` and the `PPTH` tag of the `.DAT` store device
 * paths such as `/Contents/Artist/Album/track.mp3` (the track) and `/PIONEER/USBANLZ/P065/0001A961/ANLZ0000.DAT` (its
 * analysis), relative to the root of the stick; the reference copy of that stick lives anywhere on the host.
 *
 * Paths are compared after Unicode NFC normalisation without the leading slash, because macOS file systems hand out
 * decomposed (NFD) names while rekordbox writes what it read, so the same accented name can arrive in both forms.
 */
object ReferencePaths {

    /** The file for [devicePath] (with or without the leading slash) under [root]; a path that climbs out is rejected. */
    fun resolve(root: Path, devicePath: String): Path {
        val base = root.normalize()
        val resolved = base.resolve(devicePath.trimStart('/')).normalize()
        require(resolved.startsWith(base)) { "Path leaves the export root: $devicePath" }
        return resolved
    }

    /** The `.DAT`, `.EXT` and `.2EX` files of the track whose `analyze_path` is [analyzePath] (a `.DAT` path). */
    fun anlzSet(root: Path, analyzePath: String): AnlzFileSet {
        require(analyzePath.endsWith(".DAT", ignoreCase = true)) { "An analyze path names the .DAT file: $analyzePath" }
        val stem = analyzePath.dropLast(DAT_SUFFIX_LENGTH)
        return AnlzFileSet(
            dat = resolve(root, analyzePath),
            ext = resolve(root, "$stem.EXT"),
            twoEx = resolve(root, "$stem.2EX"),
        )
    }

    /** The comparable form of a device or selection path: NFC, forward slashes, no leading slash. */
    fun key(path: String): String = Normalizer.normalize(path.replace('\\', '/').trimStart('/'), Normalizer.Form.NFC)

    /**
     * The format family of a file by its extension, lower case: `mp3`, `flac`, `wav`, `m4a`, and `aiff` for both `.aif`
     * and `.aiff` (one container); `unknown` without an extension.
     */
    fun format(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return UNKNOWN_FORMAT
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        return if (extension == "aif") "aiff" else extension
    }

    /**
     * Pairs each non-blank [selection] entry with the `export.pdb` row whose `file_path` is the same path; when a file
     * was exported twice, the row with the lowest id wins.
     */
    fun match(selection: List<String>, tracks: List<PdbTrack>): SelectionMatch {
        val byKey = HashMap<String, PdbTrack>()
        for (track in tracks.sortedBy { it.id }) byKey.putIfAbsent(key(track.filePath), track)
        val matched = ArrayList<Pair<String, PdbTrack>>()
        val unmatched = ArrayList<String>()
        for (entry in selection) {
            if (entry.isBlank()) continue
            val track = byKey[key(entry.trim())]
            if (track != null) matched += entry to track else unmatched += entry
        }
        return SelectionMatch(matched, unmatched)
    }

    const val UNKNOWN_FORMAT: String = "unknown"
    private const val DAT_SUFFIX_LENGTH = 4
}
