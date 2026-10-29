package io.sonora.core.data.archive

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ExtractedFile
import io.sonora.core.domain.archive.ImportedFolder
import io.sonora.core.domain.archive.ImportedFolders
import io.sonora.core.domain.archive.ManifestReading
import io.sonora.core.domain.port.ConversionFiles
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ImportedFolders] on shared storage: the folder listed through [ConversionFiles], its `sonora.json` read and decoded. */
class SafImportedFolders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: ConversionFiles,
) : ImportedFolders {

    override suspend fun read(folderUri: String): ImportedFolder = withContext(Dispatchers.IO) {
        val documents = files.children(folderUri).filterNot { it.isFolder }
        val manifestDocument = documents.firstOrNull { it.name.equals(ArchiveManifest.FILE_NAME, ignoreCase = true) }
        val manifest = manifestDocument?.let { readManifest(it.uri) }
        ImportedFolder(
            files = documents.filter { it !== manifestDocument }.map { ExtractedFile(it.name, it.uri) },
            manifest = manifest,
        )
    }

    private fun readManifest(uri: String): ArchiveManifest? {
        val text = try {
            context.contentResolver.openInputStream(uri.toUri())?.use { it.readAtMost(ArchiveManifest.MAX_BYTES) }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read the manifest", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read the manifest", e)
            null
        } ?: return null
        return (ArchiveManifestJson.decode(text) as? ManifestReading.Read)?.manifest
    }

    /** The stream as UTF-8 text, or `null` when it is longer than [limit] bytes. */
    private fun InputStream.readAtMost(limit: Int): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TAG = "SonoraArchive"
        const val BUFFER_BYTES = 64 * 1024
    }
}
