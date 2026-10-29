package io.sonora.core.data.conversion.saf

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.conversion.batch.ConversionInputs
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.ExpandedSources
import io.sonora.core.domain.port.StoredDocument
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Expands picked folders (SAF trees) recursively to their supported audio files ([ConversionInputs]) and passes
 * picked files and library songs through (a song with its folder, [SafUris.parentFolder]). Read access to every pick is persisted where the picker allows it, so a batch the system
 * restarts later can still read its sources.
 */
class SafConversionSourceExpander @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: ConversionFiles,
) : ConversionSourceExpander {

    override suspend fun expand(picks: List<PickedSource>): ExpandedSources {
        val seen = HashSet<String>()
        val sources = mutableListOf<BatchSource>()
        var skippedFolders = 0
        for (pick in picks) {
            persistRead(pick.uri)
            val found = when (pick) {
                is PickedSource.Folder -> walk(pick.uri).also { skippedFolders += it.skippedFolders }.sources
                is PickedSource.File -> listOf(BatchSource(pick.uri, displayName(pick.uri), parentUri = null))
                // A library song: its folder, so the original can be replaced where write access is already held.
                is PickedSource.Track -> listOf(BatchSource(pick.uri, displayName(pick.uri), SafUris.parentFolder(pick.uri)))
            }
            // Each file names its pick, so a choice made for a picked folder reaches the files found in it.
            found.filter { seen.add(it.uri) }.mapTo(sources) { it.copy(pickUri = pick.uri) }
        }
        return ExpandedSources(sources, skippedFolders)
    }

    /**
     * Supported audio below [treeUri] (hidden entries skipped), sorted by path. The picked folder itself must list; a
     * subfolder that cannot (revoked, gone, provider error) is skipped with what it holds and counted, and a folder
     * listed twice (a link back up the tree) is walked once.
     */
    private suspend fun walk(treeUri: String): ExpandedSources {
        val found = mutableListOf<Pair<String, BatchSource>>()
        var skipped = 0
        val root = SafUris.folderDocument(treeUri).toString()
        val pending = ArrayDeque(listOf(root to ""))
        val visited = hashSetOf(root)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (folder, path) = pending.removeLast()
            val children = try {
                files.children(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (folder == root) throw e
                Log.w(TAG, "Skipping a folder that cannot be listed: ${redactForLog(path)}", e)
                skipped++
                continue
            }
            for (child: StoredDocument in children) {
                if (child.name.startsWith('.')) continue
                when {
                    child.isFolder -> if (visited.add(child.uri)) pending += child.uri to "$path${child.name}/"
                    ConversionInputs.isSupported(child.name, child.mimeType) ->
                        found += "$path${child.name}" to BatchSource(child.uri, child.name, parentUri = folder)
                }
            }
        }
        return ExpandedSources(ConversionInputs.sorted(found) { it.first }.map { it.second }, skipped)
    }

    private fun persistRead(uri: String) {
        try {
            context.contentResolver.takePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable (or already persisted through a folder): read while the picker's grant lasts.
        }
    }

    private suspend fun displayName(uri: String): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri.toUri(), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: uri.toUri().lastPathSegment?.substringAfterLast('/') ?: uri
    }

    private companion object {
        const val TAG = "SonoraConversion"
    }
}
