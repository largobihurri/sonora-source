package io.sonora.core.domain.archive

import io.sonora.core.domain.model.Track

/** How far an archive job is: entries and bytes done of the total, and the entry being written. */
data class ArchiveProgress(
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String? = null,
) {
    /** Done share of the bytes, 0 to 1 (0 while the total is unknown). */
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/** Called with the progress of a running archive job, from the job's own coroutine. */
typealias ArchiveProgressListener = suspend (ArchiveProgress) -> Unit

/** Why an archive could not be written or read. Each one is a clear message for the user, never a crash. */
sealed interface ArchiveFailure {
    /** The archive is encrypted and no password was given. */
    data object PasswordRequired : ArchiveFailure

    data object WrongPassword : ArchiveFailure

    /** The password was only in memory and the app was closed while the job waited or ran: ask for it again. */
    data object PasswordNeededAgain : ArchiveFailure

    /** Not a ZIP, cut short, or its data does not match its checksums. */
    data object Damaged : ArchiveFailure

    /** No music inside (only other files, or entries rejected as unsafe). */
    data object NoMusic : ArchiveFailure

    /** [neededBytes] do not fit in the [availableBytes] left where the job writes. */
    data class NotEnoughSpace(val neededBytes: Long, val availableBytes: Long) : ArchiveFailure

    /** A song or the archive itself could not be read (moved, deleted, permission gone). */
    data class UnreadableSource(val name: String) : ArchiveFailure

    /** The destination refused the new files (permission gone, storage removed). */
    data object DestinationUnwritable : ArchiveFailure

    /** Anything else (a job that crashed); trying again may work. */
    data object Unexpected : ArchiveFailure
}

/** The end of an archive operation: a value, or a typed failure. Cancellation is a `CancellationException`. */
sealed interface ArchiveOutcome<out T> {
    data class Done<T>(val value: T) : ArchiveOutcome<T>

    data class Failed(val failure: ArchiveFailure) : ArchiveOutcome<Nothing>
}

/** Songs to pack, in order, as a [kind] archive called [name] (the album's or the playlist's title). */
data class CompressionRequest(val kind: ArchiveKind, val name: String, val tracks: List<Track>)

/** A finished archive: a local file [path] in the app's own storage, to share or save elsewhere. */
data class CompressedArchive(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val encrypted: Boolean,
)

/** What an archive is before extracting it: whether it needs a password, and its manifest when readable. */
data class ArchiveSummary(val encrypted: Boolean, val manifest: ArchiveManifest?)

/** One file written by an extraction: its [path] inside the archive and the new document's [uri]. */
data class ExtractedFile(val path: String, val uri: String)

/**
 * An extracted archive: the new folder created in the destination, the files written in archive order, the
 * archive's manifest when it is a Sonora archive this version reads, and the entries left out (unsafe or not music).
 */
data class ExtractedArchive(
    val folderUri: String,
    val files: List<ExtractedFile>,
    val manifest: ArchiveManifest?,
    val skippedEntries: List<String>,
)

/** Packs songs into one ZIP: entries stored as they are, AES-256 encrypted when a password is given. */
interface ArchiveCompressor {
    /**
     * Writes [request]'s songs and their manifest to the new file [outputPath], encrypted with [password] when not
     * `null`. The password is only read, never kept; wiping it is the caller's. A failed or cancelled run leaves no
     * file behind.
     */
    suspend fun compress(
        request: CompressionRequest,
        password: CharArray?,
        outputPath: String,
        onProgress: ArchiveProgressListener,
    ): ArchiveOutcome<CompressedArchive>
}

/** Opens ZIP archives, Sonora's or anyone's, and extracts their music. */
interface ArchiveImporter {
    /** Whether [archiveUri] needs a password, and its manifest when it is readable without one. */
    suspend fun inspect(archiveUri: String): ArchiveOutcome<ArchiveSummary>

    /**
     * Extracts the music of [archiveUri] (and its manifest) into a new folder inside [destinationFolderUri], named
     * after the archive's top folder. Entries that could land outside the new folder, and anything that is not music,
     * are skipped. A failed or cancelled run removes the new folder.
     */
    suspend fun extract(
        archiveUri: String,
        destinationFolderUri: String,
        password: CharArray?,
        onProgress: ArchiveProgressListener,
    ): ArchiveOutcome<ExtractedArchive>
}
