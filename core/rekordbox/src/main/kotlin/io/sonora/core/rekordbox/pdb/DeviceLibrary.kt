package io.sonora.core.rekordbox.pdb

import io.sonora.core.domain.analysis.MusicalKey
import java.time.LocalDate

/**
 * Everything one `export.pdb` is built from: the [tracks] on the stick, the [playlists] over them and the [exportDate]
 * (written as each track's analysis date and as the date of the sync record, since the export analyses every track).
 *
 * Artists, albums, genres, labels and keys are not given separately: [PdbWriter] collects them from the tracks and
 * numbers them 1, 2, ... in order of first use. Artwork keeps the ids the tracks give it ([DeviceTrack.artworkId]),
 * since each id also names its picture files on the stick.
 */
data class DeviceLibrary(
    val tracks: List<DeviceTrack>,
    val playlists: List<DevicePlaylist> = emptyList(),
    val exportDate: LocalDate,
) {
    init {
        val ids = tracks.map { it.id }
        require(ids.toSet().size == ids.size) { "track ids must be unique" }
        val known = ids.toSet()
        playlists.forEach { playlist ->
            playlist.trackIds.forEach { require(it in known) { "playlist '${playlist.name}' holds unknown track $it" } }
        }
    }
}

/**
 * One audio file on the stick as the players list it.
 *
 * [filePath] is the file from the stick root (`/Contents/Artist/Album/file.aiff`), [analyzePath] the `.DAT` analysis
 * file from the root (`AnlzPaths.analyzePath`); [fileName] is the last part of [filePath] unless given. Blank names
 * count as missing. [bitrateKbps] is the stream's bit rate (for 16-bit stereo 44.1 kHz PCM, 1411). [artworkId] is the
 * cover picture's id, whose files are at `ArtworkPaths.small`/`large` of it; tracks sharing a picture share the id.
 */
data class DeviceTrack(
    val id: Int,
    val title: String,
    val artist: String?,
    val album: String?,
    val tempoBpm: Double,
    val durationSeconds: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val fileSize: Long,
    val filePath: String,
    val analyzePath: String,
    val dateAdded: LocalDate,
    val genre: String? = null,
    val label: String? = null,
    val key: MusicalKey? = null,
    val fileName: String = filePath.substringAfterLast('/'),
    val trackNumber: Int? = null,
    val year: Int? = null,
    val comment: String? = null,
    val fileType: DeviceFileType = DeviceFileType.AIFF,
    val artworkId: Int? = null,
) {
    init {
        require(id > 0) { "track ids start at 1: $id" }
        require(filePath.startsWith("/")) { "file path must start at the stick root: $filePath" }
        require(analyzePath.startsWith("/")) { "analyze path must start at the stick root: $analyzePath" }
        require(fileSize in 0..0xFFFF_FFFFL) { "file size does not fit 32 bits: $fileSize" }
        require(durationSeconds in 0..0xFFFF) { "duration out of range: $durationSeconds" }
        // The row stores tempo as hundredths in a u32 written from an Int: refuse what would wrap or saturate.
        require(tempoBpm.isFinite() && tempoBpm >= 0 && tempoBpm * 100 <= Int.MAX_VALUE) {
            "tempoBpm out of range: $tempoBpm"
        }
        require(year == null || year in 0..0xFFFF) { "year does not fit 16 bits: $year" }
        require(bitDepth in 0..0xFFFF) { "bitDepth does not fit 16 bits: $bitDepth" }
        // u32 fields written from Ints: any non-negative Int fits.
        require(sampleRate >= 0) { "sampleRate must not be negative: $sampleRate" }
        require(bitrateKbps >= 0) { "bitrateKbps must not be negative: $bitrateKbps" }
        require(trackNumber == null || trackNumber >= 0) { "trackNumber must not be negative: $trackNumber" }
        require(artworkId == null || artworkId > 0) { "artwork ids start at 1: $artworkId" }
    }
}

/** A playlist at the top of the player's playlist tree: its [name] and its tracks, by [DeviceTrack.id], in order. */
data class DevicePlaylist(val name: String, val trackIds: List<Int>)

/** The audio formats as the players number them in a track's `file_type`. */
enum class DeviceFileType(val code: Int) {
    MP3(0x01),
    M4A(0x04),
    FLAC(0x05),
    WAV(0x0B),
    AIFF(0x0C),
}
