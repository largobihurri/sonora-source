package io.sonora.core.domain.library.removal

import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Deletes song files from the device's storage. Main-safe. */
interface LibraryFileDeleter {
    /** What to ask for before [uri] can be deleted; `null` when Sonora may delete it now. */
    suspend fun writeAccessFor(uri: String): WriteAccessRequest?

    /** Keeps read and write access to [pickedTreeUri] when it is the folder [request] asked for. */
    suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant

    /**
     * Deletes the file at [uri] and its library entry; `false` when the storage refused and the file is still there.
     * A file already gone counts as deleted: its entry goes too.
     */
    suspend fun delete(uri: String): Boolean
}

/** What "Delete from Phone" did. */
sealed interface DeleteResult {
    /**
     * [deleted] files are gone; [failed] could not be deleted and are still there. [cleanUpFailed] when some
     * playlists or marks could not let go of the deleted songs and still name them.
     */
    data class Deleted(val deleted: Int, val failed: Int, val cleanUpFailed: Boolean = false) : DeleteResult

    /** A folder is held read-only: nothing was deleted; pick it again to allow it, then retry. */
    data class NeedsWriteAccess(val request: WriteAccessRequest) : DeleteResult

    data object NothingToDelete : DeleteResult
}

/**
 * "Delete from Phone": deletes the songs' files for good. Write access is checked for every file first, so a
 * selection is never left half deleted because one folder is read-only. Deleted songs leave every playlist and lose
 * their favourites, pins and hidden marks (unless another file replaces them, see [uris]); a file that could not be
 * deleted keeps all of them.
 *
 * A deleted file cannot come back, so its clean-up always runs, even when the caller is cancelled between files or
 * during the clean-up, and each step runs even when another failed: the files' outcome is reported either way.
 */
class DeleteFromPhone(
    private val files: LibraryFileDeleter,
    private val playlists: PlaylistRepository,
    private val marks: LibraryMarksRepository,
) {
    suspend operator fun invoke(tracks: List<Track>): DeleteResult = uris(tracks.map { it.uri })

    /**
     * The same for songs known by their files' URIs, as the library indexes them (hidden ones included). A deleted song
     * named in [replacements] (another file of the same song) is not taken out of the playlists: its entries are moved
     * to that file, in place, and that file takes its favourite, pins and plays (see [LibraryMarksRepository.moveSongs])
     * unless it was deleted too. A file the storage refuses hands over nothing.
     */
    suspend fun uris(songUris: List<String>, replacements: Map<String, String> = emptyMap()): DeleteResult {
        val uris = songUris.distinct()
        if (uris.isEmpty()) return DeleteResult.NothingToDelete
        for (uri in uris) files.writeAccessFor(uri)?.let { return DeleteResult.NeedsWriteAccess(it) }
        val deleted = LinkedHashSet<String>()
        var cleanedUp = true
        try {
            for (uri in uris) if (deleteQuietly(uri)) deleted += uri
        } finally {
            if (deleted.isNotEmpty()) cleanedUp = withContext(NonCancellable) { forget(deleted, replacements) }
        }
        return DeleteResult.Deleted(deleted = deleted.size, failed = uris.size - deleted.size, cleanUpFailed = !cleanedUp)
    }

    /** One file; a provider failure counts as not deleted so the other files still go. */
    private suspend fun deleteQuietly(uri: String): Boolean = try {
        files.delete(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Takes the [deleted] songs out of every playlist (or moves them to their [replacements]) and drops their marks, or
     * hands them to a replacement that is still there; `false` when any step failed.
     */
    private suspend fun forget(deleted: Set<String>, replacements: Map<String, String>): Boolean {
        val leftPlaylists = leavePlaylists(deleted, replacements)
        val moves = replacements.filter { (song, replacement) -> song in deleted && replacement !in deleted }
        val forgotten = deleted - moves.keys
        val forgotMarks = forgotten.isEmpty() || step { marks.forgetSongs(forgotten) }
        val movedMarks = moves.isEmpty() || step { marks.moveSongs(moves) }
        return leftPlaylists && forgotMarks && movedMarks
    }

    /** Each playlist is edited on its own, so one that cannot be saved leaves the others clean. */
    private suspend fun leavePlaylists(deleted: Set<String>, replacements: Map<String, String>): Boolean {
        val named = try {
            playlists.playlists().first().filter { playlist -> playlist.entries.any { it.trackUri in deleted } }
        } catch (_: Exception) {
            return false
        }
        var all = true
        for (playlist in named) {
            val edited = step {
                playlists.editEntries(playlist.playlist.id) { entries ->
                    entries.mapNotNull { entry ->
                        when {
                            entry.trackUri !in deleted -> entry
                            else -> replacements[entry.trackUri]?.let { entry.copy(trackUri = it) }
                        }
                    }
                }
            }
            all = all && edited
        }
        return all
    }

    /**
     * Runs one clean-up [block]; `false` when it threw. The clean-up runs under [NonCancellable], so a
     * [CancellationException] here comes from the step itself and is one more failure.
     */
    private suspend fun step(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (_: Exception) {
        false
    }
}
