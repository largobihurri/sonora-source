package io.sonora.core.domain.export

import io.sonora.core.domain.conversion.CoverArt
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The files of a mounted USB stick, addressed by `/`-separated paths from its root without a leading slash
 * (`PIONEER/rekordbox/export.pdb`). Names compare without regard to case, as on FAT.
 *
 * Every call blocks on storage I/O: call it off the main thread. Every failure is a [StickIoException]; a missing file
 * is not a failure (`null` or `false`).
 */
interface StickFileSystem {

    /** Bytes still free on the stick, or `null` when the stick does not say. */
    fun freeBytes(): Long?

    fun exists(path: String): Boolean

    /** Size of the file at [path], or `null` when there is none. */
    fun sizeOf(path: String): Long?

    /** The whole file at [path], or `null` when there is none. */
    fun read(path: String): ByteArray?

    /**
     * Opens the file at [path] for writing from its start, creating it and its folders as needed and dropping what it
     * held. Write failures of the returned stream are [IOException]s.
     */
    fun openWrite(path: String): OutputStream

    /**
     * Puts the file at [source] in place of [target] (same folder), replacing it. Not atomic on every storage: there
     * may be a moment without [target], but never a half-written one.
     */
    fun replace(source: String, target: String)

    /** Deletes the file or the folder (with everything in it) at [path]; `false` when there was none. */
    fun delete(path: String): Boolean

    /** What the folder at [path] holds (`""` is the root), in no particular order; `null` when there is no such folder. */
    fun list(path: String): List<StickEntry>?
}

/** A file or folder in a folder of the stick. */
data class StickEntry(val name: String, val isFolder: Boolean)

/** A stick operation failed: the stick was removed, is full, read-only, or access to it was revoked. */
class StickIoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A stick the user picked (a storage tree), ready to export to. */
class StickVolume(
    /** The volume's name as the system shows it ("SONORA"). */
    val name: String,
    /**
     * The pick is the top of a removable volume, where the players look for `PIONEER` and `Contents`; a subfolder or
     * the phone's own storage is not.
     */
    val isRemovableRoot: Boolean,
    val fileSystem: StickFileSystem,
)

/** Opens the stick behind a storage tree the user picked. */
fun interface StickLocator {
    /** @throws StickIoException when the tree cannot be used (access revoked, stick removed). */
    fun open(treeUri: String): StickVolume

    /** Where the stick picker should open (the root of a plugged-in stick), or null to let the picker choose. */
    fun pickerStartUri(): String? = null
}

/** An audio file picked for export: its document [uri], [fileName], [sizeBytes] and what it holds. */
data class SourceFile(val uri: String, val fileName: String, val sizeBytes: Long, val audio: SourceAudio)

/** The tags the device library shows; `null` = missing. [cover] is the file's embedded front cover. */
data class SourceTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val comment: String? = null,
    val cover: CoverArt? = null,
) {
    companion object {
        val NONE = SourceTags()
    }
}

/** Reads the audio files picked for an export (documents the user granted). */
interface ExportSourceReader {

    /** The audio documents anywhere under the picked folder [treeUri], in path order. */
    suspend fun listAudioFiles(treeUri: String): List<String>

    /** Name, size and format of [uri]; @throws IOException when the document cannot be read. */
    suspend fun inspect(uri: String): SourceFile

    /**
     * The tags of [uri]; [SourceTags.NONE] when it has none or they cannot be read.
     * @throws java.io.IOException when the document itself cannot be read now (a failure that may pass, so it is
     *   never taken for a file without tags).
     */
    suspend fun readTags(uri: String): SourceTags

    /** The document's bytes from the start; the caller closes the stream. @throws IOException */
    fun openInput(uri: String): InputStream
}
