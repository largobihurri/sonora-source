package io.sonora.core.data.export

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.domain.export.DeviceArtworkImages
import io.sonora.core.domain.export.ExportArtwork
import io.sonora.core.domain.log.redactForLog
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [ExportArtwork] from the library: a track the library indexes gets the picture it shows for it when the export finds
 * none in its tags (the embedded picture as the scanner cached it, else its folder's image); pictures are encoded by
 * [DeviceArtworkEncoder].
 */
@Singleton
class LibraryExportArtwork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
) : ExportArtwork {

    override suspend fun libraryPicture(documentUri: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val track = dao.trackByUri(documentUri) ?: return@withContext null
            listOfNotNull(track.embeddedArtworkUri, track.folderImageUri).firstNotNullOfOrNull(::readPicture)
        } catch (e: CancellationException) {
            // The export's own cancellation goes on; one of the library's (its database closed) is the library failing,
            // which must not read as the export cancelled.
            ensureActive()
            Log.w(TAG, "Library picture of ${redactForLog(documentUri)} unavailable; exporting without it", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Library picture of ${redactForLog(documentUri)} unavailable; exporting without it", e)
            null
        }
    }

    override fun deviceImages(picture: ByteArray): DeviceArtworkImages? = try {
        DeviceArtworkEncoder.encode(picture)
    } catch (e: Exception) {
        Log.w(TAG, "Cover unreadable; exporting without it", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Cover too large to scale; exporting without it", e)
        null
    }

    /** The picture at [uri], `null` when it cannot be read or is larger than [MAX_PICTURE_BYTES]. */
    private fun readPicture(uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(uri.toUri())?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_PICTURE_BYTES) return@use null
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Picture ${redactForLog(uri)} unreadable", e)
        null
    }

    private companion object {
        const val TAG = "SonoraExport"
        const val BUFFER_BYTES = 64 * 1024

        /** Larger than any cover a library holds in practice; a bound so a stray huge file cannot exhaust memory. */
        const val MAX_PICTURE_BYTES = 32 shl 20
    }
}
