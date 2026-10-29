package io.sonora.core.domain.archive

import io.sonora.core.domain.port.ArchiveImportRepository
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/** One song an import brought: its extracted file ([ArchiveImportRules.documentKey]), its title and its artist. */
data class ImportedSong(val documentKey: String, val title: String, val artist: String)

/**
 * One finished archive import: the archive's [archiveName], when its music was extracted ([importedAtMs]), the folder
 * it went into ([destinationTreeUri]), the new folder made there ([folderUri], a document reached through that tree),
 * the playlist it recreated ([playlistId], if any) and how many songs it brought ([songCount]).
 */
data class ArchiveImportEntry(
    val id: Long,
    val archiveName: String,
    val importedAtMs: Long,
    val destinationTreeUri: String,
    val folderUri: String,
    val playlistId: Long? = null,
    val songCount: Int = 0,
)

/** An import just recorded: its history [id] and its [songs] in order. */
data class RecordedImport(val id: Long, val songs: List<ImportedSong>)

/** A song's title and artist as its own tags give them; `null` when a tag is missing. */
data class SongCredits(val title: String?, val artist: String?)

/** Reads an extracted song's tags, for a file the archive's manifest does not describe. */
fun interface ExtractedSongTags {
    /** `null` when the file cannot be read. */
    suspend fun read(file: ExtractedFile): SongCredits?
}

/** Keeps the history of archive imports as each one's music is extracted. */
interface ImportRecorder {
    /**
     * Records the import of [archiveName] whose music was extracted into [folderUri], inside [destinationTreeUri],
     * with the songs found there; returns the record.
     */
    suspend fun record(archiveName: String, destinationTreeUri: String, folderUri: String): RecordedImport

    /** Notes the playlist [playlistId] that import [importId] recreated. */
    suspend fun playlistMade(importId: Long, playlistId: Long)
}

/**
 * [ImportRecorder]: lists the extracted folder and records its songs. A Sonora archive's songs come in the archive's
 * order with the titles and artists of its manifest; any other file follows by name, with its own tags (a file
 * without a title, or that cannot be read, goes by its file name).
 */
class RecordArchiveImport(
    private val imports: ArchiveImportRepository,
    private val imported: ImportedFolders,
    private val tags: ExtractedSongTags,
    private val clock: Clock,
) : ImportRecorder {

    override suspend fun record(archiveName: String, destinationTreeUri: String, folderUri: String): RecordedImport {
        val songs = songs(imported.read(folderUri))
        val entry = ArchiveImportEntry(0, archiveName, clock.millis(), destinationTreeUri, folderUri)
        return RecordedImport(imports.add(entry, songs), songs)
    }

    override suspend fun playlistMade(importId: Long, playlistId: Long) = imports.setPlaylist(importId, playlistId)

    private suspend fun songs(folder: ImportedFolder): List<ImportedSong> {
        val entries = folder.manifest?.entries.orEmpty()
        val order = entries.withIndex().associate { (index, entry) -> entry.file.lowercase() to index }
        val byName = entries.associateBy { it.file.lowercase() }
        val files = folder.files.sortedWith(
            compareBy<ExtractedFile> { order[it.path.lowercase()] ?: Int.MAX_VALUE }.thenBy { it.path.lowercase() },
        )
        return files.map { file ->
            val key = ArchiveImportRules.documentKey(file.uri) ?: file.uri
            val fallback = file.path.substringBeforeLast('.').ifEmpty { file.path }
            val described = byName[file.path.lowercase()]
            if (described != null) {
                ImportedSong(key, described.title.ifBlank { fallback }, described.artist)
            } else {
                val credits = readTags(file)
                ImportedSong(key, credits?.title?.takeIf { it.isNotBlank() } ?: fallback, credits?.artist?.trim().orEmpty())
            }
        }
    }

    private suspend fun readTags(file: ExtractedFile): SongCredits? = try {
        tags.read(file)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** "Remove from History": forgets an import's record; its folder, songs and playlist stay. */
class ForgetArchiveImport(private val imports: ArchiveImportRepository) {
    suspend operator fun invoke(importId: Long) = imports.remove(importId)
}
