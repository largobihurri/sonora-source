package io.sonora.core.data.archive

import android.content.ContentResolver
import android.content.Context
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.archive.ArchiveCompressor
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ArchiveNaming
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveProgressListener
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.archive.ExtractedArchive
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConversionFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs an archive operation on the I/O dispatcher, its [ArchiveFailureException] as a failed outcome. */
private suspend fun <T> outcome(block: suspend () -> T): ArchiveOutcome<T> = withContext(Dispatchers.IO) {
    try {
        ArchiveOutcome.Done(block())
    } catch (e: ArchiveFailureException) {
        ArchiveOutcome.Failed(e.failure)
    }
}

/** Opens [uri] for reading; any failure (gone, permission revoked, provider dead) is [ArchiveFailure.UnreadableSource]. */
private fun ContentResolver.openOrFail(uri: String, name: String): InputStream = try {
    openInputStream(uri.toUri())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw ArchiveFailureException(ArchiveFailure.UnreadableSource(name), e)
} ?: throw ArchiveFailureException(ArchiveFailure.UnreadableSource(name))

/** [uri]'s display name and size as its provider tells them; either may be `null`. */
private fun ContentResolver.describe(uri: String): Pair<String?, Long?> = try {
    query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null to null
        val name = if (cursor.isNull(0)) null else cursor.getString(0)
        val size = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 }
        name to size
    } ?: (null to null)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null to null
}

/**
 * [ArchiveCompressor] over the songs' documents: sizes from their providers (the library's when they do not tell),
 * the archive written to `<outputPath>.part` and renamed once complete, so a failed or cancelled run leaves nothing.
 */
class ZipArchiveCompressor @Inject constructor(@ApplicationContext private val context: Context) : ArchiveCompressor {

    private val resolver = context.contentResolver
    private val writer = ZipArchiveWriter()

    override suspend fun compress(
        request: CompressionRequest,
        password: CharArray?,
        outputPath: String,
        onProgress: ArchiveProgressListener,
    ): ArchiveOutcome<CompressedArchive> = outcome {
        if (request.tracks.isEmpty()) throw ArchiveFailureException(ArchiveFailure.NoMusic)
        val layout = ArchiveNaming.layout(request.kind, request.name, request.tracks)
        val manifestBytes = ArchiveManifestJson.encode(layout.manifest).toByteArray(Charsets.UTF_8)
        val manifest = ArchiveSource(layout.manifestPath, manifestBytes.size.toLong(), ArchiveManifest.FILE_NAME) {
            ByteArrayInputStream(manifestBytes)
        }
        val sources = request.tracks.mapIndexed { index, track ->
            val name = track.title.ifBlank { layout.manifest.entries[index].file }
            ArchiveSource(layout.entryPath(index), sizeOf(track), name) { resolver.openOrFail(track.uri, name) }
        }

        val output = File(outputPath)
        val partial = File("$outputPath.part")
        val folder = output.absoluteFile.parentFile
        folder?.mkdirs()
        val needed = manifest.sizeBytes + sources.sumOf { it.sizeBytes } + ENTRY_OVERHEAD * (sources.size + 1)
        val available = folder?.let(::freeBytes) ?: 0
        if (folder != null && available < needed) throw ArchiveFailureException(ArchiveFailure.NotEnoughSpace(needed, available))

        try {
            val stream = try {
                FileOutputStream(partial)
            } catch (e: IOException) {
                throw ArchiveFailureException(ArchiveFailure.DestinationUnwritable, e)
            }
            writer.write(stream, manifest, sources, password, onProgress)
            if (!partial.renameTo(output)) throw ArchiveFailureException(ArchiveFailure.DestinationUnwritable)
        } catch (e: Throwable) {
            partial.delete()
            if (e is ArchiveFailureException && e.failure is ArchiveFailure.NotEnoughSpace) {
                throw ArchiveFailureException(ArchiveFailure.NotEnoughSpace(needed, folder?.let(::freeBytes) ?: 0), e)
            }
            throw e
        }
        CompressedArchive(
            path = output.path,
            fileName = output.name,
            sizeBytes = output.length(),
            entryCount = sources.size,
            encrypted = password != null && password.isNotEmpty(),
        )
    }

    /** Bytes that can be written in [folder], counting cached data the system would clear to make room. */
    private fun freeBytes(folder: File): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(folder))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        folder.usableSpace // the volume is not one StorageManager knows (or it failed to answer)
    }

    private fun sizeOf(track: Track): Long = resolver.describe(track.uri).second?.takeIf { it > 0 } ?: track.sizeBytes

    private companion object {
        /** Bytes a ZIP adds per entry at most (headers, AES salt and MAC, central directory), with the name. */
        const val ENTRY_OVERHEAD = 1_024L
    }
}

/** [ArchiveImporter] reading archives from any content URI and extracting them into a shared-storage folder. */
class ZipArchiveImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val files: ConversionFiles,
) : ArchiveImporter {

    private val resolver = context.contentResolver
    private val reader = ZipArchiveReader()

    override suspend fun inspect(archiveUri: String): ArchiveOutcome<ArchiveSummary> = outcome {
        val name = resolver.describe(archiveUri).first ?: archiveUri.substringAfterLast('/')
        resolver.openOrFail(archiveUri, name).use { reader.inspect(it) }
    }

    override suspend fun extract(
        archiveUri: String,
        destinationFolderUri: String,
        password: CharArray?,
        onProgress: ArchiveProgressListener,
    ): ArchiveOutcome<ExtractedArchive> = outcome {
        val (described, size) = resolver.describe(archiveUri)
        val name = described ?: archiveUri.toUri().lastPathSegment?.substringAfterLast('/') ?: ArchiveNaming.DEFAULT_NAME
        val sink = SafExtractionSink(resolver, files, destinationFolderUri)
        resolver.openOrFail(archiveUri, name).use { reader.extract(it, name, size, password, sink, onProgress) }
    }
}
