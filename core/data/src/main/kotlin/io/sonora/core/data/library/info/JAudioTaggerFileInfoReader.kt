package io.sonora.core.data.library.info

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.saf.AudioFiles
import io.sonora.core.domain.audio.AiffHeader
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.FileDetails
import io.sonora.core.domain.model.FileLocation
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import io.sonora.core.domain.port.TrackFileInfoReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TrackFileInfoReader] over SAF: location from the library index, size and date from the provider, stream and tag
 * details read with JAudioTagger ([AudioDetailsReader]: `/proc/self/fd/N` when readable, else a temporary copy, like
 * the scanner's tag reader). AIFF stream details come from the shared AIFF parser. Formats JAudioTagger cannot read
 * (Opus, raw AAC, a damaged file) still give the file and the indexed stream details, with tags reported as
 * unavailable; a file read without any tag has empty tags.
 */
@Singleton
class JAudioTaggerFileInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
) : TrackFileInfoReader {

    override suspend fun read(track: Track): TrackFileInfo = withContext(Dispatchers.IO) {
        val indexed = dao.trackByUri(track.uri)
        val libraryFolder = indexed?.let { dao.folder(it.folderTreeUri)?.displayName }
        val uri = track.uri.toUri()
        val provider = providerDetails(uri)
        val fileName = provider.name ?: indexed?.fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: track.title
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("No descriptor for ${track.uri}")
        descriptor.use {
            val readerKey = AudioFiles.readerExtension(fileName, track.mimeType)
            val details = readerKey?.let { key -> detailsReader.read(key, readablePath(it), copyTo = { out -> copy(it, out) }) }
            val aiff = details?.aiff
            TrackFileInfo(
                location = FileLocation(
                    libraryFolder = libraryFolder,
                    relativePath = FileInfoMapper.relativePath(indexed?.folderPath ?: track.folderPath, libraryFolder),
                    fileName = fileName,
                    contentUri = track.uri,
                ),
                file = FileDetails(
                    container = FileInfoMapper.container(fileName, track.mimeType, isAifc = aiff?.isAifc == true),
                    sizeBytes = provider.size ?: it.statSize.takeIf { size -> size >= 0 } ?: indexed?.sizeBytes,
                    lastModifiedMs = provider.lastModifiedMs ?: indexed?.lastModifiedMs?.takeIf { ms -> ms > 0 },
                ),
                audio = details?.audio ?: indexedAudio(track, indexed, fileName, aiff),
                tags = details?.tags,
            )
        }
    }

    private val detailsReader = AudioDetailsReader(context.cacheDir, ::imageSize)

    /** The document's `/proc/self/fd` path when it can be reopened by path (not under scoped storage, as a rule). */
    private fun readablePath(descriptor: ParcelFileDescriptor): File? =
        File("/proc/self/fd/${descriptor.fd}").takeIf { it.canRead() }

    /** Copies the whole document from its start, whatever the descriptor's current offset. */
    private fun copy(descriptor: ParcelFileDescriptor, out: OutputStream) {
        val input = FileInputStream(descriptor.fileDescriptor) // not closed here: [descriptor] owns the fd
        input.channel.position(0)
        input.copyTo(out)
    }

    /** What the index (and the AIFF header, when there is one) knows when JAudioTagger cannot read the file. */
    private fun indexedAudio(track: Track, indexed: TrackEntity?, fileName: String, aiff: AiffHeader?): AudioStreamInfo {
        if (aiff != null) return FileInfoMapper.audio(RawAudioHeader(), "aiff", aiff)
        val codec = FileInfoMapper.codecWithoutReader(fileName, track.mimeType)
        return AudioStreamInfo(
            codec = codec,
            encoder = null,
            sampleRateHz = indexed?.sampleRateHz ?: track.sampleRateHz,
            sampleFormat = if (codec != null) SampleFormat.Lossy else (indexed?.bitsPerSample ?: track.bitsPerSample)?.let { SampleFormat.Integer(it) },
            channelCount = indexed?.channelCount ?: track.channelCount,
            bitrateKbps = null,
            variableBitrate = false,
            durationMs = track.durationMs.takeIf { it > 0 },
        )
    }

    private class ProviderDetails(val name: String?, val size: Long?, val lastModifiedMs: Long?)

    /** Name, size and modification date as the document provider reports them now; nulls when it does not say. */
    private fun providerDetails(uri: Uri): ProviderDetails = try {
        context.contentResolver.query(uri, PROVIDER_COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ProviderDetails(
                name = cursor.string(OpenableColumns.DISPLAY_NAME),
                size = cursor.long(OpenableColumns.SIZE)?.takeIf { it >= 0 },
                lastModifiedMs = cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
            )
        } ?: ProviderDetails(null, null, null)
    } catch (e: Exception) {
        if (e is SecurityException) throw e // access revoked: the file cannot be opened either
        ProviderDetails(null, null, null)
    }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    /** Pixel size of an embedded picture, decoding only its header. */
    private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private companion object {
        val PROVIDER_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
