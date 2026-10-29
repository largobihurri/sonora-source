package io.sonora.core.domain.library.duplicates

import io.sonora.core.domain.library.TrackTags

/**
 * One indexed file as the duplicate finder compares it: its location, the tags it was matched by and what the
 * comparison view shows about its quality. [title] and [artist] are the library's display names; [tagTitle] and
 * [tagArtist] are the file's own tags (`null` when untagged), the only ones a song match trusts.
 */
data class DuplicateCopy(
    val uri: String,
    val title: String,
    val artist: String,
    val tagTitle: String?,
    val tagArtist: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    /** The file's modification time as the scanner last saw it; with [sizeBytes], what tells a changed file. */
    val lastModifiedMs: Long,
    /** Short format name: the scanner's codec, else what the MIME type tells; `null` when unknown. */
    val format: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val channelCount: Int?,
    /** Containing directory, '/'-separated, from the library folder's display name. */
    val folderPath: String,
    val fileName: String,
    /** A picture is embedded in the file itself (not a folder image or another song's). */
    val hasArtwork: Boolean,
    /** How many of the [TAG_FIELDS] common tags the file has. */
    val tagsFilled: Int,
    val dateAddedSec: Long,
    /** SHA-256 of the file's bytes, hex; only files whose size another file shares are hashed. */
    val contentHash: String? = null,
) {
    companion object {
        /** Title, artist, album, album artist, genre, year and track number. */
        const val TAG_FIELDS = 7

        /** How many of the [TAG_FIELDS] common tags [tags] holds. */
        fun tagsFilled(tags: TrackTags): Int = listOf(
            tags.title, tags.artist, tags.album, tags.albumArtist, tags.genre, tags.year, tags.trackNumber,
        ).count { it != null && (it !is String || it.isNotBlank()) }
    }
}

/** Why a group's copies count as the same: the very same bytes, or the same song in different files. */
enum class DuplicateKind { IDENTICAL, SAME_SONG }

/**
 * Copies of one song, best first ([DuplicateQuality]): [suggested] is the one to keep. [key] names the group by its
 * files, so it changes when a copy comes or goes.
 */
data class DuplicateGroup(val copies: List<DuplicateCopy>) {
    val key: String get() = keyOf(copies.map { it.uri })

    val suggested: DuplicateCopy get() = copies.first()

    /** [DuplicateKind.IDENTICAL] when every copy was hashed to the same content. */
    val kind: DuplicateKind
        get() {
            val hashes = copies.map { it.contentHash }
            return if (hashes.none { it == null } && hashes.distinct().size == 1) DuplicateKind.IDENTICAL else DuplicateKind.SAME_SONG
        }

    companion object {
        /** A stable name for a set of files, whatever their order. */
        fun keyOf(uris: Collection<String>): String = uris.distinct().sorted().joinToString("\n")
    }
}
