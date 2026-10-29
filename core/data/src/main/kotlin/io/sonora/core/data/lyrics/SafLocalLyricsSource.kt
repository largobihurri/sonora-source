package io.sonora.core.data.lyrics

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.conversion.saf.SafUris
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.saf.AudioFiles
import io.sonora.core.domain.lyrics.LocalLyrics
import io.sonora.core.domain.lyrics.Lyrics
import io.sonora.core.domain.lyrics.LyricsOrigin
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LocalLyricsSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LocalLyricsSource] for library songs, which are SAF tree documents: first a sidecar `.lrc` in the song's folder
 * (listed through the tree; the folder comes from the document id when it is a path, else from the provider's
 * document path), then the song's embedded lyrics ([EmbeddedLyrics]), read from the descriptor's `/proc/self/fd` path
 * when it is readable and from a temporary copy otherwise, like File Info. A source that fails is logged and skipped.
 */
@Singleton
class SafLocalLyricsSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
) : LocalLyricsSource {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun read(track: Track): LocalLyrics? = withContext(Dispatchers.IO) {
        val sidecar = safely("sidecar") { sidecar(track) }
        if (sidecar != null) return@withContext LocalLyrics(sidecar, LyricsOrigin.SIDECAR)
        safely("embedded") { embedded(track) }?.let { LocalLyrics(it, LyricsOrigin.EMBEDDED) }
    }

    private inline fun safely(source: String, read: () -> Lyrics?): Lyrics? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Lyrics: $source lyrics unreadable", e)
        null
    } catch (e: LinkageError) {
        Log.w(TAG, "Lyrics: tag reader unavailable", e)
        null
    }

    private fun sidecar(track: Track): Lyrics? {
        val tree = SafUris.treeOf(track.uri) ?: return null
        val document = track.uri.toUri()
        val folderId = folderId(track.uri, document) ?: return null
        val children = children(tree, folderId)
        val ownId = DocumentsContract.getDocumentId(document)
        val audioName = children.firstOrNull { it.first == ownId }?.second ?: return null
        val name = LyricsFiles.sidecarName(audioName, children.map { it.second }) ?: return null
        val sidecarId = children.first { it.second == name }.first
        val bytes = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, sidecarId))?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_SIDECAR_BYTES) throw IOException("$name is too large for a lyrics file")
            }
            out.toByteArray()
        } ?: return null
        return Lyrics.parse(LyricsFiles.decode(bytes))
    }

    /** Document id of the folder holding [document]. */
    private fun folderId(uri: String, document: Uri): String? {
        SafUris.parentFolder(uri)?.let { return DocumentsContract.getDocumentId(it.toUri()) }
        // Opaque ids: ask the provider for the path from the tree's root to the document.
        val path = try {
            DocumentsContract.findDocumentPath(resolver, document)?.path
        } catch (e: UnsupportedOperationException) {
            null
        }
        return path?.takeIf { it.size >= 2 }?.let { it[it.size - 2] }
    }

    /** (document id, display name) of the folder's entries. */
    private fun children(tree: Uri, folderId: String): List<Pair<String, String>> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        return resolver.query(listing, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    add(id to name)
                }
            }
        }.orEmpty()
    }

    private suspend fun embedded(track: Track): Lyrics? {
        val fileName = dao.trackByUri(track.uri)?.fileName ?: track.uri.toUri().lastPathSegment?.substringAfterLast('/').orEmpty()
        val readerKey = AudioFiles.readerExtension(fileName, track.mimeType) ?: return null
        val descriptor = resolver.openFileDescriptor(track.uri.toUri(), "r") ?: return null
        return descriptor.use {
            val direct = File("/proc/self/fd/${it.fd}")
            if (direct.canRead()) EmbeddedLyrics.read(direct, readerKey) else readCopy(it, readerKey)
        }
    }

    /** Providers whose descriptors cannot be reopened by path (scoped storage, as a rule) are read from a copy. */
    private fun readCopy(descriptor: ParcelFileDescriptor, readerKey: String): Lyrics? {
        val copy = File.createTempFile("lyrics", ".$readerKey", context.cacheDir)
        try {
            val input = FileInputStream(descriptor.fileDescriptor) // not closed here: [descriptor] owns the fd
            input.channel.position(0)
            copy.outputStream().use { input.copyTo(it) }
            return EmbeddedLyrics.read(copy, readerKey)
        } finally {
            copy.delete()
        }
    }

    private companion object {
        const val TAG = "SonoraLyrics"

        /** Real LRC files are a few kilobytes; anything far larger is not one. */
        const val MAX_SIDECAR_BYTES = 1 shl 20

        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
    }
}
