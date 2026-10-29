package io.sonora.core.djanalysis

import android.content.ContentResolver
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.sonora.core.djengine.identity.AudioPayloadHasher
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.AudioIdentifier
import io.sonora.core.djmodel.ByteSource
import io.sonora.core.djmodel.FileStamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The audio documents the identifier reads: their [stamp] for the memo and their bytes. */
interface AudioDocuments {

    /** Size and last-modified time of [sourceUri] as its provider reports them now, or `null` unless it reports both. */
    fun stamp(sourceUri: String): FileStamp?

    /**
     * Opens [sourceUri] for positional reads; the caller closes it.
     *
     * @throws java.io.IOException when it cannot be opened.
     */
    fun open(sourceUri: String): ByteSource
}

/** [AudioDocuments] served by the [ContentResolver] (SAF documents of the library folders). */
class SafAudioDocuments(private val resolver: ContentResolver) : AudioDocuments {

    override fun stamp(sourceUri: String): FileStamp? = try {
        resolver.query(sourceUri.toUri(), STAMP_COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)?.takeIf { it >= 0 }
            val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it > 0 }
            if (size != null && modified != null) FileStamp(size, modified) else null
        }
    } catch (e: SecurityException) {
        throw e // access revoked: opening the file fails too, so say so now
    } catch (e: Exception) {
        null // a provider that cannot answer: no memo, the bytes decide
    }

    override fun open(sourceUri: String): ByteSource = SafByteSource.open(resolver, sourceUri.toUri())

    private companion object {
        val STAMP_COLUMNS = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}

/**
 * The [AudioIdentifier] of the app: the payload hash of `:core:djengine` ([AudioPayloadHasher]), cancellable between
 * reads, on [dispatcher], plus a memo by uri ([AudioIdIndex]) so a known file is not read again.
 *
 * The memo counts only when the provider reports the file's size and last-modified time and both are what they were
 * when the id was remembered ([FileStamp]); a file without a stamp is always hashed (the memo is a cache of a value the
 * bytes can always give, so a doubtful entry is never trusted). Cancellation is a `CancellationException` from the
 * caller's coroutine, checked before every read of the hasher, never an identification failure.
 */
class SafAudioIdentifier(
    private val documents: AudioDocuments,
    private val index: AudioIdIndex,
    private val hasher: AudioPayloadHasher = AudioPayloadHasher(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioIdentifier {

    override suspend fun identify(source: ByteSource): AudioId = withContext(dispatcher) {
        val context = currentCoroutineContext()
        hasher.identify(source) { context.ensureActive() }
    }

    /**
     * The id of the audio at [sourceUri]: from the memo when its stamp still matches, else hashed and remembered.
     *
     * @throws java.io.IOException when the file cannot be read.
     */
    suspend fun identify(sourceUri: String): AudioId {
        val stamp = withContext(dispatcher) { documents.stamp(sourceUri) }
        if (stamp != null) index.idFor(sourceUri, stamp)?.let { return it }
        val id = withContext(dispatcher) { documents.open(sourceUri) }.use { identify(it) }
        index.remember(sourceUri, id, stamp)
        return id
    }
}
