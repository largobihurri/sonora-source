package io.sonora.core.domain.port

import io.sonora.core.domain.model.Track
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagChanges

/** Why a file's tags cannot be read or saved. */
enum class TagEditFailure {
    /** No tag writer for the file's format (only MP3, FLAC, M4A, WAV and AIFF are edited). */
    UNSUPPORTED_FORMAT,

    /** The file's location cannot be written: no folder grant to ask for, or the storage refuses writes. */
    READ_ONLY_LOCATION,

    /** The file cannot be opened or its tags parsed. */
    UNREADABLE,

    /** Too little free space for the working copies a safe save needs. */
    NOT_ENOUGH_SPACE,

    /** The rewritten copy did not read back as written; the file was left unchanged. */
    NOT_VERIFIED,

    /** Writing failed before the file was touched, or the original was put back; the file is unchanged. */
    NOT_SAVED,

    /** Replacing the file failed and the original could not be put back; the file may be damaged. */
    INTERRUPTED,
}

/** A library folder Sonora may only read. [pickerStartUri] opens the system folder picker on it. */
data class WriteAccessRequest(val treeUri: String, val folderName: String, val pickerStartUri: String)

sealed interface TagReadOutcome {
    data class Read(val tags: SongTags) : TagReadOutcome

    data class Failed(val failure: TagEditFailure) : TagReadOutcome
}

sealed interface TagWriteOutcome {
    /** Saved and verified; [track] carries the new details, as the library now lists them. */
    data class Written(val track: Track) : TagWriteOutcome

    /** The folder holding the file was granted read-only; nothing was written. */
    data class NeedsWriteAccess(val request: WriteAccessRequest) : TagWriteOutcome

    data class Failed(val failure: TagEditFailure) : TagWriteOutcome
}

/** The result of re-picking a folder to allow changes. */
enum class WriteGrant {
    GRANTED,

    /** The user picked another folder; its grant was not kept. */
    WRONG_FOLDER,

    /** The folder was picked but its storage does not allow writes. */
    REFUSED,
}

/**
 * Reads and writes the editable tags of a track's file. Main-safe. A save never leaves a half-written file: the tags
 * are written into a copy, the copy is read back and checked, and only then does it replace the file's contents. On
 * success the library's entry for the file is updated at once, without a rescan.
 */
interface SongTagEditor {
    suspend fun read(track: Track): TagReadOutcome

    suspend fun write(track: Track, changes: TagChanges): TagWriteOutcome

    /** Keeps read and write access to [pickedTreeUri] when it is the folder [request] asked for. */
    suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant
}

/** Loads a picture the user picked (a content URI) for embedding, scaled down when it is very large. */
fun interface PictureLoader {
    /** The picture, or `null` when the image cannot be read or decoded. */
    suspend fun load(uri: String): Picture?
}

/** Shows a file's new details in the play queue. */
fun interface QueuedTrackUpdater {
    /**
     * Every queue entry for [track]'s file ([Track.uri]) takes [track]'s details. The entry playing keeps playing; it
     * is reloaded from its file at the same position, since the file's bytes changed under the player.
     */
    fun update(track: Track)
}
