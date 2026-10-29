package io.sonora.core.domain.library

import java.text.Normalizer
import java.util.Locale

/**
 * Groups scanned files into albums and artists by their tags, never by folder alone:
 * - album key = normalized album artist (fallback: track artist) + normalized album title;
 * - disc numbers do not split albums, and one album spread over several folders stays one album;
 * - compilation flag without album artist, or several track artists sharing an album title inside one album folder
 *   with no album artist, makes one "Various Artists" album (an explicit album artist always wins);
 * - Various Artists albums are also keyed by their album folder (the containing folder, or its parent for disc
 *   folders such as `CD1`), because unrelated compilations often share generic titles ("Greatest Hits"). A
 *   compilation spread over unrelated folders therefore splits;
 * - untagged files: title from the file name, album from the containing folder (one album per folder),
 *   artist unknown.
 * Ids are hashes of normalized keys, so they survive rescans and reinstalls.
 */
object AlbumGrouping {
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val VARIOUS_ARTISTS = "Various Artists"

    private const val KEY_SEPARATOR = '\u0001'
    private val WHITESPACE = Regex("\\s+")
    private val DISC_FOLDER = Regex("(?i)(cd|disc|disk)[\\s._-]*\\d+")
    private val VARIOUS_ARTISTS_KEY = normalize(VARIOUS_ARTISTS)

    /** Resolves every track, keyed by [ScannedTrack.documentUri]. */
    fun resolve(tracks: List<ScannedTrack>): Map<String, ResolvedTrack> {
        val bases = tracks.map(::base)
        val sharedTitles = bases
            .filter { it.albumArtistTag == null && !it.track.tags.isCompilation }
            .groupBy { it.folderAlbumKey }
            .filterValues { group -> group.distinctBy { normalize(it.artist) }.size > 1 }
            .keys
        val placed = bases.map { base ->
            val compilation = base.track.tags.isCompilation || base.folderAlbumKey in sharedTitles
            val albumArtist = base.albumArtistTag ?: if (compilation) VARIOUS_ARTISTS else base.artist
            val albumArtistKey = normalize(albumArtist)
            val folderKey = if (albumArtistKey == VARIOUS_ARTISTS_KEY) "$KEY_SEPARATOR${base.albumFolderKey}" else ""
            Placed(base, albumArtist, compilation, albumArtistKey + KEY_SEPARATOR + base.albumTitleKey + folderKey)
        }
        val artistNames = (placed.map { it.base.artist } + placed.map { it.albumArtist })
            .groupBy(::normalize)
            .mapValues { (_, spellings) -> mostFrequent(spellings) }

        return buildMap {
            for ((albumKey, group) in placed.groupBy { it.albumKey }) {
                val albumTitle = mostFrequent(group.map { it.base.albumTitle })
                val albumArtistKey = normalize(group.first().albumArtist)
                val isCompilation = group.any { it.compilation }
                val ordered = group.map { it.base.track }.sortedWith(ALBUM_ORDER)
                val artwork = ordered.firstNotNullOfOrNull { it.embeddedArtworkUri }
                    ?: ordered.firstNotNullOfOrNull { it.folderImageUri }
                for (member in group) {
                    val artistKey = normalize(member.base.artist)
                    put(
                        member.base.track.documentUri,
                        ResolvedTrack(
                            title = member.base.title,
                            artist = artistNames.getValue(artistKey),
                            artistId = stableId("artist", artistKey),
                            album = albumTitle,
                            albumId = stableId("album", albumKey),
                            albumArtist = artistNames.getValue(albumArtistKey),
                            albumArtistId = stableId("artist", albumArtistKey),
                            isCompilation = isCompilation,
                            artworkUri = artwork,
                        ),
                    )
                }
            }
        }
    }

    /** Comparison form of a tag: Unicode NFKC, lower case, inner whitespace collapsed, trimmed. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    /** Positive, non-zero 64-bit FNV-1a hash of `namespace:key`. */
    fun stableId(namespace: String, key: String): Long {
        var hash = FNV_OFFSET
        for (byte in "$namespace:$key".encodeToByteArray()) {
            hash = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME
        }
        return (hash and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }

    private fun base(track: ScannedTrack): Base {
        val tags = track.tags
        val albumTag = TagText.clean(tags.album)
        val folderName = TagText.clean(track.folderPath.substringAfterLast('/'))
        return Base(
            track = track,
            title = TagText.clean(tags.title)
                ?: TagText.clean(track.fileName.substringBeforeLast('.'))
                ?: track.fileName,
            artist = TagText.clean(tags.artist) ?: UNKNOWN_ARTIST,
            albumArtistTag = TagText.clean(tags.albumArtist),
            albumTitle = albumTag ?: folderName ?: UNKNOWN_ALBUM,
            // Without an album tag the folder is the album, so same-named folders elsewhere stay apart.
            albumTitleKey = albumTag?.let(::normalize) ?: "folder:${normalize(track.folderPath)}",
        )
    }

    /** The folder an album lives in: [folderPath], or its parent when [folderPath] is a disc folder (`CD1`, `Disc 2`). */
    private fun albumFolder(folderPath: String): String {
        val name = folderPath.substringAfterLast('/')
        return if ('/' in folderPath && DISC_FOLDER.matches(name)) folderPath.substringBeforeLast('/') else folderPath
    }

    /** Most common spelling; ties go to the first in code-point order so the choice is deterministic. */
    private fun mostFrequent(spellings: List<String>): String =
        spellings.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

    private data class Base(
        val track: ScannedTrack,
        val title: String,
        val artist: String,
        val albumArtistTag: String?,
        val albumTitle: String,
        val albumTitleKey: String,
    ) {
        val albumFolderKey = "folder:${normalize(albumFolder(track.folderPath))}"
        val folderAlbumKey get() = albumFolderKey to albumTitleKey
    }

    private data class Placed(
        val base: Base,
        val albumArtist: String,
        val compilation: Boolean,
        val albumKey: String,
    )

    private val ALBUM_ORDER = compareBy<ScannedTrack> { it.tags.discNumber ?: 0 }
        .thenBy { it.tags.trackNumber ?: Int.MAX_VALUE }
        .thenBy { it.documentUri }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
}
