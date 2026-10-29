package io.sonora.core.data.library.scan

import android.util.Log
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.LocationUpdate
import io.sonora.core.data.library.db.TagReadFailureEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.saf.LibraryWalker
import io.sonora.core.data.library.tags.ArtworkCache
import io.sonora.core.data.library.tags.TagReadResult
import io.sonora.core.data.library.tags.TagReader
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.ScanDiff
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ScanSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The scan engine over the SAF library folders and the Room index. Per folder: walk, diff against the index,
 * drop removed files, read tags only for new or changed files (in transactions of [BATCH_SIZE]), then regroup
 * the whole library once so albums spanning folders resolve. Rows always carry a usable provisional grouping,
 * so an interrupted scan leaves a consistent, browsable index. Files that cannot be read right now keep their
 * previous entry (or none) and are read again by a later scan once [ReadRetry.RETRY_SPACING_MS] passed, so a burst of
 * scans during one outage counts once; a new or changed file that fails [ReadRetry.MAX_ATTEMPTS] spaced reads is
 * indexed untagged instead, so it still reaches the library, and that entry is read again up to
 * [ReadRetry.MAX_REREADS] times, [ReadRetry.REREAD_SPACING_MS] apart, while the file is unchanged (counts and times
 * are stored in the index and start over once the file is read, changes again or leaves the folder). Rows written by an
 * older scanner ([TrackEntity.DETAILS_VERSION]) are read once more to fill the columns added since. Callers go through
 * [LibraryScanCoordinator], which never runs two scans at once; the mutex here only guards direct callers.
 */
@Singleton
class LibraryScanService @Inject constructor(
    private val dao: LibraryDao,
    private val walker: LibraryWalker,
    private val tagReader: TagReader,
    private val artworkCache: ArtworkCache,
    private val clock: ScanClock,
) {

    private val mutex = Mutex()
    private val state = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
    val progress: StateFlow<ScanProgress> = state.asStateFlow()

    /**
     * Scans every library folder; suspends until done. Cancelling keeps what was indexed so far. Progress always
     * ends in [ScanProgress.Finished], or [ScanProgress.Idle] when there was nothing to scan or the scan failed.
     */
    suspend fun scan(): ScanSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            var summary: ScanSummary? = null
            var scanned = false
            try {
                val folders = dao.folderList()
                scanned = folders.isNotEmpty()
                summary = folders.fold(ScanSummary()) { total, folder -> total + scanFolder(folder) }
            } finally {
                try {
                    withContext(NonCancellable) { regroup() }
                } catch (e: Exception) {
                    // Rows keep their provisional per-batch grouping and the next scan regroups; a scan failure
                    // already propagating stays the one reported.
                    Log.w(TAG, "Regrouping the library failed", e)
                } finally {
                    state.value = summary?.takeIf { scanned }?.let { ScanProgress.Finished(it) } ?: ScanProgress.Idle
                }
            }
            checkNotNull(summary)
        }
    }

    private suspend fun scanFolder(folder: LibraryFolderEntity): ScanSummary {
        state.value = ScanProgress.Scanning(folder.displayName, scanned = 0, total = 0)
        val listing = try {
            walker.walk(folder.treeUri, folder.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list ${redactForLog(folder.displayName)}; keeping its indexed tracks", e)
            return ScanSummary(failedFolders = 1)
        }
        val indexed = dao.indexedFiles(folder.treeUri).associateBy { it.documentUri }
        val listed = listing.associateBy { it.documentUri }
        val diff = ScanDiff.compute(indexed.values.map { it.identity }, listing.map { it.identity })

        val lostArtwork = diff.unchanged.filter { file ->
            indexed.getValue(file.documentUri).embeddedArtworkUri?.let(artworkCache::isMissing) == true
        }
        val moved = diff.unchanged.mapNotNull { file ->
            val row = indexed.getValue(file.documentUri)
            val now = listed.getValue(file.documentUri)
            if (row.folderPath == now.folderPath && row.folderImageUri == now.folderImageUri) {
                null
            } else {
                LocationUpdate(now.documentUri, now.folderPath, now.folderImageUri)
            }
        }
        dao.applyListing(removed = diff.removed.map { it.documentUri }, moved = moved)

        val nowMs = clock.nowMs()
        val added = diff.added.mapTo(HashSet()) { it.documentUri }
        val changed = diff.changed.mapTo(HashSet()) { it.documentUri }
        val unchanged = diff.unchanged.mapTo(HashSet()) { it.documentUri }
        // Pending counts only matter for versions the index does not hold yet, fallback markers only for the untagged
        // entry still listed unchanged; any other row (file read since, gone, or a stale count) starts over.
        val failures = dao.readFailures(folder.treeUri).associateBy { it.documentUri }
        dao.deleteReadFailures(
            failures.values.filterNot { row ->
                val uri = row.documentUri
                uri in added || uri in changed || (uri in unchanged && ReadRetry.isFallback(row.failures))
            }.map { it.documentUri },
        )
        val (due, notDue) = (diff.added + diff.changed).partition { file ->
            val row = failures[file.documentUri]
            row == null || ReadRetry.retryDue(row.failures, row.lastFailureMs, nowMs)
        }
        val rereads = diff.unchanged.filter { file ->
            val row = failures[file.documentUri]
            row != null && ReadRetry.rereadDue(row.failures, row.lastFailureMs, nowMs)
        }
        val rereadUris = rereads.mapTo(HashSet()) { it.documentUri }
        // Indexed by an older scanner: read once more for the details it did not store. Untagged fallbacks follow
        // their own re-read schedule instead, and one that cannot be read now simply keeps its entry.
        val outdated = diff.unchanged.filter { file ->
            indexed.getValue(file.documentUri).detailsVersion < TrackEntity.DETAILS_VERSION && file.documentUri !in failures
        }
        val outdatedUris = outdated.mapTo(HashSet()) { it.documentUri }
        val toRead = (due + lostArtwork + rereads + outdated).distinctBy { it.documentUri }.map { listed.getValue(it.documentUri) }
        val stored = HashSet<String>()
        // Files whose current version could not be read: not due for a retry yet, or failed now.
        val unread = notDue.mapTo(HashSet()) { it.documentUri }
        var scanned = listing.size - toRead.size
        state.value = ScanProgress.Scanning(folder.displayName, scanned, listing.size)
        val nowSec = nowMs / 1000
        for (batch in toRead.chunked(BATCH_SIZE)) {
            // Removed while scanning: its remaining files are no longer part of the library.
            if (dao.folder(folder.treeUri) == null) break
            val failed = ArrayList<TagReadFailureEntity>()
            val read = batch.mapNotNull { file ->
                currentCoroutineContext().ensureActive()
                val uri = file.documentUri
                val row = failures[uri]
                var result = tagReader.read(file)
                if (result == null) {
                    if (uri in added || uri in changed) {
                        val count = ReadRetry.pendingAttempts(row?.failures ?: 0) + 1
                        if (count >= ReadRetry.MAX_ATTEMPTS) {
                            Log.w(TAG, "Cannot read ${redactForLog(file.fileName)} after $count attempts; indexing it without tags")
                            result = TagReadResult.UNTAGGED
                        }
                        failed += TagReadFailureEntity(uri, folder.treeUri, count, nowMs)
                    } else if (row != null && uri in rereadUris) {
                        // A scheduled re-read of an untagged fallback failed again: keep the entry, count the attempt.
                        // Any other read of it (e.g. for lost artwork) leaves the re-read schedule alone.
                        failed += row.copy(failures = row.failures + 1, lastFailureMs = nowMs)
                    }
                }
                if (result == null && uri !in rereadUris && uri !in outdatedUris) unread += uri
                result?.let {
                    file to ScannedTrack(
                        file.documentUri, file.folderPath, file.fileName, it.tags, it.embeddedArtworkUri, file.folderImageUri,
                    )
                }
            }
            read.mapTo(stored) { it.first.documentUri }
            val provisional = AlbumGrouping.resolve(read.map { it.second })
            val stillFailing = failed.mapTo(HashSet()) { it.documentUri }
            dao.storeBatch(
                tracks = read.map { (file, track) ->
                    TrackEntity.of(
                        file = track,
                        folderTreeUri = folder.treeUri,
                        mimeType = file.mimeType,
                        identity = file.identity,
                        dateAddedSec = indexed[file.documentUri]?.dateAddedSec ?: nowSec,
                        resolution = provisional.getValue(file.documentUri),
                    )
                },
                failures = failed,
                cleared = read.map { it.first.documentUri }.filter { it in failures && it !in stillFailing },
            )
            scanned += batch.size
            state.value = ScanProgress.Scanning(folder.displayName, scanned, listing.size)
        }
        return ScanSummary(
            added = added.count { it in stored },
            changed = changed.count { it in stored },
            unchanged = diff.unchanged.size,
            removed = diff.removed.size,
            skipped = unread.size,
        )
    }

    /** Applies the grouping rules to the whole index and drops cached pictures nothing references. */
    private suspend fun regroup() {
        dao.deleteOrphanTracks()
        dao.deleteOrphanReadFailures()
        val all = dao.allTracks()
        val resolution = AlbumGrouping.resolve(all.map { it.toScanned() })
        val updated = all.mapNotNull { row ->
            resolution[row.documentUri]?.let(row::withResolution)?.takeIf { it != row }
        }
        if (updated.isNotEmpty()) dao.updateTracks(updated)
        artworkCache.prune(all.mapNotNullTo(HashSet()) { it.embeddedArtworkUri })
    }

    private companion object {
        const val TAG = "SonoraScan"
        const val BATCH_SIZE = 25
    }
}
