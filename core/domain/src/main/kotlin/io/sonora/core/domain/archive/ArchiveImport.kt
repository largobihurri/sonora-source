package io.sonora.core.domain.archive

import io.sonora.core.domain.library.LibraryFolderRules
import io.sonora.core.domain.library.TreeLocation
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import java.net.URLDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What an extraction left in its new folder: the files directly in it, and the Sonora manifest when there is one. */
data class ImportedFolder(val files: List<ExtractedFile>, val manifest: ArchiveManifest?)

/** Reads a folder an extraction made. */
fun interface ImportedFolders {
    /** The files directly in [folderUri] ([ExtractedFile.path] is the file's name) and its readable manifest. */
    suspend fun read(folderUri: String): ImportedFolder
}

/**
 * Where an import's music stands in the library. Before the import, [IN_LIBRARY] (the destination is inside a library
 * folder) or [JOINS_LIBRARY] (the extracted folder will be offered to join it). After it, [IN_LIBRARY], [JOINS_LIBRARY]
 * (the extracted folder was added) or [OUTSIDE_LIBRARY] (it was not: the user declined, picked another folder, or the
 * add failed; it can still be added).
 */
enum class DestinationCoverage { IN_LIBRARY, JOINS_LIBRARY, OUTSIDE_LIBRARY }

/** Manifest entries found in the library, in manifest order, and how many were not. */
data class TrackMatch(val tracks: List<Track>, val missing: Int)

/** The rules of an import: where the music lands in the library, and how a Sonora archive's list comes back. */
object ArchiveImportRules {

    private val DOCUMENT_URI = Regex("content://([^/]+)/(?:tree/[^/]+/)?document/([^/]+)")

    /**
     * Whether the music extracted into [destinationTreeUri] is in the library given its [folders] (the destination is
     * inside one), or its new folder is to join it. A destination that holds library folders is fine: only the
     * extracted folder joins, and a new folder holds none.
     */
    fun coverage(destinationTreeUri: String, folders: List<LibraryFolder>): DestinationCoverage =
        when (LibraryFolderRules.conflict(destinationTreeUri, folders)) {
            is FolderConflict.AlreadyAdded, is FolderConflict.InsideExisting -> DestinationCoverage.IN_LIBRARY
            null, is FolderConflict.ContainsExisting -> DestinationCoverage.JOINS_LIBRARY
        }

    /**
     * Whether [treeUri], picked with the folder picker opened at [extractedFolderUri], is that very folder (same
     * provider and document id), the only folder an import adds to the library.
     */
    fun isExtractedFolder(treeUri: String, extractedFolderUri: String): Boolean {
        val picked = LibraryFolderRules.location(treeUri) ?: return false
        val extracted = documentKey(extractedFolderUri) ?: return false
        return "${picked.authority}/${picked.documentId.trimEnd('/')}" == extracted.trimEnd('/')
    }

    /**
     * The playlist an import recreates, named as in the archive: playlists and selections come back as a playlist in
     * archive order; an album regroups by itself from its songs' tags, and a single song or a ZIP without a manifest
     * (`null`) bring only the music.
     */
    fun playlistName(manifest: ArchiveManifest?): String? = when (manifest?.kind) {
        ArchiveKind.PLAYLIST, ArchiveKind.SELECTION -> manifest.name.trim().ifEmpty { null }
        ArchiveKind.ALBUM, ArchiveKind.SONG, null -> null
    }

    /**
     * The same document however it is reached: provider plus decoded document id, for `content://<provider>/document/<id>`
     * and `content://<provider>/tree/<tree>/document/<id>`; `null` for anything else.
     */
    fun documentKey(uri: String): String? {
        val (authority, encodedId) = DOCUMENT_URI.matchEntire(uri)?.destructured ?: return null
        // Uri.encode escapes '+' itself, so a literal '+' is part of the name, not an encoded space.
        val id = runCatching { URLDecoder.decode(encodedId.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull() ?: return null
        return "$authority/$id"
    }

    /** Library folders that are the extracted folder [folderUri] (a document URI) or hold it, in their order. */
    fun foldersHolding(folderUri: String, folders: List<LibraryFolder>): List<LibraryFolder> {
        val key = documentKey(folderUri) ?: return emptyList()
        val extracted = TreeLocation(key.substringBefore('/'), key.substringAfter('/'))
        return folders.filter { folder ->
            LibraryFolderRules.location(folder.treeUri)?.let { LibraryFolderRules.contains(it, extracted) } == true
        }
    }

    /** The extracted documents of [manifest]'s entries in order: each entry's file found by name, ignoring case. */
    fun entryDocuments(manifest: ArchiveManifest, files: List<ExtractedFile>): List<String?> {
        val byName = files.associateBy { it.path.lowercase() }
        return manifest.entries.map { byName[it.file.lowercase()]?.uri?.let(::documentKey) }
    }

    /** [manifest]'s entries as the library's [tracks], in manifest order, matched through [files] by document. */
    fun matchTracks(manifest: ArchiveManifest, files: List<ExtractedFile>, tracks: List<Track>): TrackMatch {
        val byDocument = tracks.associateBy { documentKey(it.uri) }
        val matched = entryDocuments(manifest, files).mapNotNull { key -> key?.let(byDocument::get) }
        return TrackMatch(matched, missing = manifest.entries.size - matched.size)
    }
}

/**
 * How an import ended once its music is extracted: where it stands in the library and the playlist made, if any
 * ([playlistId] is its id, kept in the import history so deleting the import deletes it too).
 */
data class ImportOutcome(
    val coverage: DestinationCoverage,
    val playlistName: String?,
    val playlistSongs: Int,
    val missing: Int,
    val playlistId: Long? = null,
)

/** The end of an import once its music is extracted into [extractedFolderUri], inside [destinationTreeUri]. */
interface ImportFinisher {
    /** Recreates the archive's playlist when a library folder covers [destinationTreeUri]; adds no folder. */
    suspend fun finish(destinationTreeUri: String, extractedFolderUri: String): ImportOutcome

    /** Adds [folderTreeUri], the extracted folder the user allowed, as a library folder, then recreates the playlist. */
    suspend fun addAndFinish(folderTreeUri: String, extractedFolderUri: String): ImportOutcome
}

/**
 * [ImportFinisher]: the end of an import, after the extraction. Music extracted inside a library folder is in the
 * library already; otherwise only the extracted folder itself joins it, once the user allows it with the folder
 * picker ([addAndFinish]; the add also scans it). Then, for a playlist or selection archive, it waits for the scan to
 * list the new songs (at most `waitMs`) and recreates the list in archive order with the songs found.
 */
class FinishArchiveImport(
    private val folders: LibraryFolderRepository,
    private val imported: ImportedFolders,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
) : ImportFinisher {
    override suspend fun finish(destinationTreeUri: String, extractedFolderUri: String): ImportOutcome =
        invoke(destinationTreeUri, extractedFolderUri)

    override suspend fun addAndFinish(folderTreeUri: String, extractedFolderUri: String): ImportOutcome =
        addAndFinish(folderTreeUri, extractedFolderUri, WAIT_MS)

    suspend operator fun invoke(destinationTreeUri: String, extractedFolderUri: String, waitMs: Long = WAIT_MS): ImportOutcome {
        val coverage = ArchiveImportRules.coverage(destinationTreeUri, folders.folders().first())
        if (coverage != DestinationCoverage.IN_LIBRARY) return outside()
        return recreate(DestinationCoverage.IN_LIBRARY, extractedFolderUri, waitMs)
    }

    suspend fun addAndFinish(folderTreeUri: String, extractedFolderUri: String, waitMs: Long): ImportOutcome {
        if (!ArchiveImportRules.isExtractedFolder(folderTreeUri, extractedFolderUri)) return outside()
        val coverage = when (val added = folders.add(folderTreeUri)) {
            is AddFolderResult.Added -> DestinationCoverage.JOINS_LIBRARY
            is AddFolderResult.Rejected -> when (added.conflict) {
                is FolderConflict.AlreadyAdded, is FolderConflict.InsideExisting -> DestinationCoverage.IN_LIBRARY
                is FolderConflict.ContainsExisting -> return outside()
            }
        }
        return recreate(coverage, extractedFolderUri, waitMs)
    }

    private fun outside() = ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0)

    private suspend fun recreate(coverage: DestinationCoverage, extractedFolderUri: String, waitMs: Long): ImportOutcome {
        val folder = imported.read(extractedFolderUri)
        val manifest = folder.manifest
        val name = ArchiveImportRules.playlistName(manifest)
        if (manifest == null || name == null) return ImportOutcome(coverage, playlistName = null, playlistSongs = 0, missing = 0)
        val wanted = ArchiveImportRules.entryDocuments(manifest, folder.files).filterNotNull().toSet()
        val listed = withTimeoutOrNull(waitMs) {
            library.tracks().first { tracks -> tracks.mapNotNullTo(HashSet()) { ArchiveImportRules.documentKey(it.uri) }.containsAll(wanted) }
        } ?: library.tracks().first()
        val match = ArchiveImportRules.matchTracks(manifest, folder.files, listed)
        if (match.tracks.isEmpty()) return ImportOutcome(coverage, playlistName = null, playlistSongs = 0, missing = match.missing)
        val playlistId = playlists.create(name, description = "", entries = match.tracks.map(PlaylistEntry::of))
        return ImportOutcome(coverage, name, match.tracks.size, match.missing, playlistId)
    }

    companion object {
        /** How long the playlist waits for the scan: a big library's first scan of a new folder takes a while. */
        const val WAIT_MS: Long = 5 * 60 * 1000L
    }
}
