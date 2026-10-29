package io.sonora.core.data.library.edit

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.saf.AudioFiles
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagReadOutcome
import io.sonora.core.domain.port.TagWriteOutcome
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import io.sonora.core.domain.tags.TagChanges
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SongTagEditor] over SAF documents: tags are read from the document (by its `/proc/self/fd` path when readable, else
 * from a copy) and saved through [SafeTagRewrite], whose working copies live in `cacheDir/tag-edit` and which replaces
 * the document's contents through a `"rwt"` descriptor. Before writing, the song's tree must be held with write access
 * ([SafWriteAccess]); a document outside any tree is simply tried. Saves run one at a time.
 */
@Singleton
class SafSongTagEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
    private val writeAccess: SafWriteAccess,
    private val retag: LibraryRetag,
) : SongTagEditor {

    private val resolver: ContentResolver get() = context.contentResolver
    private val workDir = File(context.cacheDir, "tag-edit")
    private val rewrite = SafeTagRewrite(workDir, JAudioTaggerTagCodec)
    private val saving = Mutex()

    override suspend fun read(track: Track): TagReadOutcome = withContext(Dispatchers.IO) {
        val readerKey = writableKey(track) ?: return@withContext TagReadOutcome.Failed(TagEditFailure.UNSUPPORTED_FORMAT)
        try {
            val descriptor = resolver.openFileDescriptor(track.uri.toUri(), "r") ?: throw FileNotFoundException(track.uri)
            descriptor.use { TagReadOutcome.Read(readTags(it, readerKey)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the tags to edit", e)
            TagReadOutcome.Failed(TagEditFailure.UNREADABLE)
        } catch (e: LinkageError) {
            Log.w(TAG, "Tag reader unavailable", e)
            TagReadOutcome.Failed(TagEditFailure.UNREADABLE)
        }
    }

    override suspend fun write(track: Track, changes: TagChanges): TagWriteOutcome = withContext(Dispatchers.IO) {
        saving.withLock {
            val readerKey = writableKey(track) ?: return@withLock TagWriteOutcome.Failed(TagEditFailure.UNSUPPORTED_FORMAT)
            writeAccess.requestFor(track.uri)?.let { return@withLock TagWriteOutcome.NeedsWriteAccess(it) }
            val uri = track.uri.toUri()
            when (val result = rewrite.rewrite(SafDocument(resolver, uri), readerKey, changes)) {
                is RewriteResult.Failed -> TagWriteOutcome.Failed(result.failure)
                is RewriteResult.Done -> TagWriteOutcome.Written(index(track, result.written))
            }
        }
    }

    override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant =
        withContext(Dispatchers.IO) { writeAccess.grant(request, pickedTreeUri) }

    /** The library's entry with the new tags; the file is saved either way, so an index failure is left to the scan. */
    private suspend fun index(track: Track, written: FileTags): Track = try {
        val (size, modified) = identity(track.uri.toUri())
        retag.store(track, written.tags, size, modified)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tags saved, but the library entry was not updated; the next scan will", e)
        track
    }

    /** The reader key when the file's format is edited ([JAudioTaggerTagCodec.WRITABLE]). */
    private suspend fun writableKey(track: Track): String? {
        val name = dao.trackByUri(track.uri)?.fileName ?: displayName(track.uri.toUri()) ?: track.uri.substringAfterLast('/')
        return AudioFiles.readerExtension(name, track.mimeType)?.takeIf { it in JAudioTaggerTagCodec.WRITABLE }
    }

    private fun readTags(descriptor: ParcelFileDescriptor, readerKey: String) =
        File("/proc/self/fd/${descriptor.fd}").takeIf { it.canRead() }?.let { JAudioTaggerTagCodec.read(it, readerKey).tags }
            ?: run {
                workDir.mkdirs()
                val copy = File.createTempFile("read", ".$readerKey", workDir)
                try {
                    val input = FileInputStream(descriptor.fileDescriptor) // owned by [descriptor]
                    input.channel.position(0)
                    copy.outputStream().use { input.copyTo(it) }
                    JAudioTaggerTagCodec.read(copy, readerKey).tags
                } finally {
                    copy.delete()
                }
            }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Size and modification date the provider reports for the rewritten file; nulls when it does not say. */
    private fun identity(uri: Uri): Pair<Long?, Long?> = runCatching {
        val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun long(index: Int) = if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it > 0 } else null
            long(cursor.getColumnIndex(columns[0])) to long(cursor.getColumnIndex(columns[1]))
        }
    }.getOrNull() ?: (null to null)

    /** A SAF document as [SafeTagRewrite] reaches it. */
    private class SafDocument(private val resolver: ContentResolver, private val uri: Uri) : TagDocument {
        override fun size(): Long? =
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size >= 0 } }

        override fun copyTo(out: OutputStream) {
            (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { it.copyTo(out) }
        }

        /** `"rwt"` truncates on open; closing syncs the bytes to storage before the descriptor goes. */
        override fun truncateAndOpen(): OutputStream {
            val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: throw FileNotFoundException(uri.toString())
            return object : ParcelFileDescriptor.AutoCloseOutputStream(descriptor) {
                override fun close() {
                    try {
                        flush()
                        runCatching { fd.sync() }
                    } finally {
                        super.close()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "SonoraTagEdit"
    }
}
