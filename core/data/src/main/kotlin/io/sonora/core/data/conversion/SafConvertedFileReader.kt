package io.sonora.core.data.conversion

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.saf.ListedAudioFile
import io.sonora.core.data.library.tags.TagReader
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ConvertedFileReader] over SAF: the output document's name, type and size from its provider (which also tells
 * whether it still exists and is readable), then its tags and embedded picture through the library's [TagReader], as
 * the library scan reads files. A document that is gone, refused or unreadable is `null`; nothing throws.
 */
class SafConvertedFileReader @Inject constructor(
    @ApplicationContext context: Context,
    private val tagReader: TagReader,
) : ConvertedFileReader {

    private val resolver: ContentResolver = context.contentResolver

    override suspend fun read(uri: String, fileName: String): ConvertedFile? = withContext(Dispatchers.IO) {
        try {
            val document = describe(uri) ?: return@withContext null
            val mimeType = document.mimeType ?: "audio/*"
            val file = ListedAudioFile(
                documentUri = uri,
                fileName = document.name ?: fileName,
                mimeType = mimeType,
                sizeBytes = document.sizeBytes,
                lastModifiedMs = document.lastModifiedMs,
                folderPath = "",
                folderImageUri = null,
            )
            val read = tagReader.read(file) ?: return@withContext null
            ConvertedFile(read.tags, read.embeddedArtworkUri, mimeType, document.sizeBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Converted file unavailable: $uri", e)
            null
        }
    }

    private fun describe(uri: String): Described? {
        val cursor = resolver.query(uri.toUri(), PROJECTION, null, null, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            Described(
                name = it.getString(0),
                mimeType = it.getString(1),
                sizeBytes = if (it.isNull(2)) 0L else it.getLong(2),
                lastModifiedMs = if (it.isNull(3)) 0L else it.getLong(3),
            )
        }
    }

    private class Described(val name: String?, val mimeType: String?, val sizeBytes: Long, val lastModifiedMs: Long)

    private companion object {
        const val TAG = "SafConvertedFileReader"
        val PROJECTION = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
