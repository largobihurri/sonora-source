package io.sonora.core.rekordbox.manifest

import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.export.ExportText
import io.sonora.core.rekordbox.pdb.DeviceFileType
import io.sonora.core.rekordbox.pdb.DeviceLibrary
import io.sonora.core.rekordbox.pdb.DevicePlaylist
import io.sonora.core.rekordbox.pdb.DeviceTrack
import java.io.IOException
import java.time.LocalDate

/**
 * Sonora's record of the device library on a stick, kept at [PATH] next to the `export.pdb` it was built from.
 *
 * `export.pdb` is regenerated as a whole on every export, so each export reads this manifest, adds its tracks and
 * playlist, writes the new `export.pdb` from it and saves it again. It holds what the library needs and nothing the
 * stick does not already show: per track the tags, the stick paths of the audio and analysis files, the file's size
 * and SHA-256 (to recognise a track exported before) and the analysis summary the track row stores; per cover picture
 * on the stick its id and a hash of its files (to share one picture between the tracks that have it).
 */
data class StickManifest(
    val tracks: List<ManifestTrack> = emptyList(),
    val playlists: List<ManifestPlaylist> = emptyList(),
    val artwork: List<ManifestArtwork> = emptyList(),
) {
    /** The id the next added track gets: one more than the highest so far. */
    val nextTrackId: Int get() = (tracks.maxOfOrNull { it.id } ?: 0) + 1

    /** The id the next added picture gets: one more than the highest so far. */
    val nextArtworkId: Int get() = (artwork.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * The device library for `export.pdb`: tags cleaned and bounded so every row fits a page, implausible numbers
     * dropped, playlist entries of unknown tracks and pictures the manifest does not list left out. [exportDate]
     * becomes the analysis date of every track.
     */
    fun toDeviceLibrary(exportDate: LocalDate): DeviceLibrary {
        val known = tracks.map { it.id }.toSet()
        val pictures = artwork.map { it.id }.toSet()
        return DeviceLibrary(
            tracks = tracks.map { it.toDeviceTrack(pictures) },
            playlists = playlists.map { playlist ->
                DevicePlaylist(
                    name = ExportText.clamp(playlist.name) ?: UNTITLED_PLAYLIST,
                    trackIds = playlist.trackIds.filter { it in known },
                )
            },
            exportDate = exportDate,
        )
    }

    private fun ManifestTrack.toDeviceTrack(pictures: Set<Int>): DeviceTrack {
        val bits = bitDepth.coerceIn(0, MAX_U16)
        val bitrate = (sampleRate.toLong() * bits * channelCount.coerceAtLeast(1) / 1000)
            .coerceIn(0, Int.MAX_VALUE.toLong())
        return DeviceTrack(
            id = id,
            title = ExportText.clamp(title)
                ?: ExportText.clamp(filePath.substringAfterLast('/').substringBeforeLast('.'))
                ?: UNTITLED_TRACK,
            artist = ExportText.clamp(artist),
            album = ExportText.clamp(album),
            // Out-of-range tempos would not fit the row's hundredths: no tempo rather than a failed export.
            tempoBpm = bpm.takeIf { it.isFinite() && it >= 0 && it * 100 <= Int.MAX_VALUE } ?: 0.0,
            durationSeconds = durationSeconds.coerceIn(0, MAX_U16),
            sampleRate = sampleRate.coerceAtLeast(0),
            bitDepth = bits,
            bitrateKbps = bitrate.toInt(),
            fileSize = fileSize,
            filePath = filePath,
            analyzePath = analyzePath,
            dateAdded = dateAdded,
            genre = ExportText.clamp(genre),
            key = key,
            trackNumber = ExportText.trackNumber(trackNumber),
            year = ExportText.year(year),
            fileType = fileType,
            comment = ExportText.clamp(comment),
            artworkId = artworkId?.takeIf { it in pictures },
        )
    }

    companion object {
        /** Where the manifest lives on the stick, from its root. */
        const val PATH = "PIONEER/sonora/library.json"

        private const val MAX_U16 = 0xFFFF
        private const val UNTITLED_TRACK = "Untitled"
        private const val UNTITLED_PLAYLIST = "Playlist"
    }
}

/** One exported track. Paths are from the stick root with a leading `/`, as in `export.pdb`. */
data class ManifestTrack(
    val id: Int,
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val trackNumber: Int?,
    val year: Int?,
    val filePath: String,
    val analyzePath: String,
    val fileSize: Long,
    /** Lower-case hex SHA-256 of the audio file. */
    val contentSha256: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val durationSeconds: Int,
    val bpm: Double,
    val key: MusicalKey?,
    val dateAdded: LocalDate,
    val fileType: DeviceFileType = DeviceFileType.AIFF,
    /** `TrackAnalysis.analyzerVersion` of the analysis behind the ANLZ files. */
    val analyzerVersion: String = "",
    /** The source's comment, cleaned and bounded like the other tags (on one line). */
    val comment: String? = null,
    /** The cover picture ([ManifestArtwork.id]), `null` when the track has none. */
    val artworkId: Int? = null,
    /**
     * [comment] and [artworkId] were looked for in the source. `false` only for tracks a Sonora from before covers
     * and comments listed: the next export that meets their source again fills them in.
     */
    val extrasRead: Boolean = true,
)

/**
 * A cover picture on the stick, `ArtworkPaths.small`/`large` of its [id]; [sha256] is the lower-case hex SHA-256 of
 * its two JPEG files (large then small), by which tracks with the same cover share it.
 */
data class ManifestArtwork(val id: Int, val sha256: String)

/** A playlist at the top of the player's playlist tree, with its tracks by [ManifestTrack.id] in order. */
data class ManifestPlaylist(val name: String, val trackIds: List<Int>, val created: LocalDate? = null)

/** The manifest on the stick cannot be used: damaged, not Sonora's, or written by a newer Sonora. */
sealed class ManifestFormatException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Malformed(detail: String, cause: Throwable? = null) : ManifestFormatException("Malformed manifest: $detail", cause)

    class NewerVersion(val version: Int) : ManifestFormatException("Manifest version $version is newer than this app")
}
