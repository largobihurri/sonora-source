package io.sonora.core.data.conversion

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.saf.AudioFiles
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.port.SourceMetadataReader
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SourceMetadataReader] over SAF documents and `file://` URIs: the reader is chosen from the display name or MIME
 * type, the document is read through `/proc/self/fd/N` when that path is readable, else from a temporary copy
 * ([SourceMetadataFileReader]), and the cover is prepared by [AndroidCoverArt]. Every failure gives `null` and a
 * warning: the conversion goes ahead untagged. That includes a tag too large to read ([OutOfMemoryError]) and a
 * missing tag library ([LinkageError]), which [SourceMetadataFileReader] maps where JAudioTagger runs.
 */
@Singleton
class JAudioTaggerSourceMetadataReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : SourceMetadataReader {

    private val files = SourceMetadataFileReader(context.cacheDir, AndroidCoverArt::prepare)

    override suspend fun read(sourceUri: String): TrackMetadata? = withContext(Dispatchers.IO) {
        try {
            val uri = sourceUri.toUri()
            val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val readerKey = AudioFiles.readerExtension(name, mimeType)
            if (readerKey == null) {
                // The extension, never the file name: release logs carry no user content.
                Log.w(TAG, "No tag reader for a .${name.substringAfterLast('.', "")} file ($mimeType); converting without tags")
                return@withContext null
            }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                files.read(readerKey, readablePath(descriptor), copyTo = { out -> copy(descriptor, out) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Source tags unavailable; converting without them", e)
            null
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
        }
    }.getOrNull()

    /** The `/proc/self/fd` path when the descriptor can be reopened by path (not under scoped storage, as a rule). */
    private fun readablePath(descriptor: ParcelFileDescriptor): File? =
        File("/proc/self/fd/${descriptor.fd}").takeIf { it.canRead() }

    /** Copies the whole document from its start; [descriptor] keeps owning the fd. */
    private fun copy(descriptor: ParcelFileDescriptor, out: OutputStream) {
        val input = FileInputStream(descriptor.fileDescriptor)
        input.channel.position(0)
        input.copyTo(out)
    }

    private companion object {
        const val TAG = "SonoraMetadata"
    }
}
