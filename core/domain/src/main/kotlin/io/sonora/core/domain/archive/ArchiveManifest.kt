package io.sonora.core.domain.archive

/** What a Sonora archive holds: one song, an album, a playlist, or songs picked in a list (a selection). */
enum class ArchiveKind { SONG, ALBUM, PLAYLIST, SELECTION }

/**
 * One audio file of an archive, in archive order, with the tags of the track it was made from. [file] is the file's
 * name inside the archive's folder (the folder holding the manifest), never a path.
 */
data class ArchiveEntry(
    val file: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0,
)

/**
 * The `sonora.json` file Sonora writes into every archive, next to the music: enough to recreate the album or
 * playlist in order on another device. Other programs ignore it and see only the music.
 */
data class ArchiveManifest(val kind: ArchiveKind, val name: String, val entries: List<ArchiveEntry>) {
    companion object {
        /** The `format` value of every Sonora manifest; any other value is someone else's file. */
        const val FORMAT_ID: String = "sonora-archive"

        /**
         * The manifest version written. A reader accepts this version and older ones, never a newer one. New optional
         * fields may come within a version (readers ignore unknown fields); a new [ArchiveKind] needs a new version,
         * as a reader rejects a kind it does not know.
         */
        const val VERSION: Int = 1

        const val FILE_NAME: String = "sonora.json"

        /** Largest manifest read, so a hostile archive cannot fill the memory with one entry. */
        const val MAX_BYTES: Int = 4 * 1024 * 1024
    }
}

/** Why a `sonora.json` is not read as a Sonora manifest. */
sealed interface ManifestError {
    /** Another program's file, or no format id at all. */
    data class OtherFormat(val formatId: String?) : ManifestError

    /** Written by a newer Sonora in a version this one cannot read. */
    data class NewerVersion(val version: Int) : ManifestError

    /** Not JSON, or missing or wrongly typed fields. */
    data class Malformed(val reason: String) : ManifestError
}

/** The result of reading a `sonora.json`. */
sealed interface ManifestReading {
    data class Read(val manifest: ArchiveManifest) : ManifestReading

    data class Rejected(val error: ManifestError) : ManifestReading
}
