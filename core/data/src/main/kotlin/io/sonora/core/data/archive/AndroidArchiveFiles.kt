package io.sonora.core.data.archive

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.archive.ArchiveFiles
import io.sonora.core.domain.archive.CompressedArchive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ArchiveFiles] for the archives in the app cache (`cache/archives/`): shared through the app's FileProvider
 * ([authority], declared in the app manifest with that folder only), saved by copying through the content resolver.
 */
class AndroidArchiveFiles @Inject constructor(@ApplicationContext private val context: Context) : ArchiveFiles {

    override fun shareUri(archive: CompressedArchive): String =
        FileProvider.getUriForFile(context, authority(context), File(archive.path)).toString()

    override suspend fun saveTo(archive: CompressedArchive, documentUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = context.contentResolver.openOutputStream(documentUri.toUri(), "wt")
                ?: throw IOException("Cannot write $documentUri")
            output.use { out -> FileInputStream(archive.path).use { it.copyTo(out, BUFFER_BYTES) } }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException, SecurityException, or a provider that rejects the mode.
            Log.w(TAG, "Cannot save the archive", e)
            false
        }
    }

    companion object {
        private const val TAG = "SonoraArchive"
        private const val BUFFER_BYTES = 256 * 1024

        /** The FileProvider authority of the archive cache; the app manifest declares the same one. */
        fun authority(context: Context): String = "${context.packageName}.archives"
    }
}
