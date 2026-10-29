package io.sonora.feature.cdjexport.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.ExportSourceReader
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceAudioProbe
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.domain.port.TrackFileInfoReader
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads picked documents through the content resolver: folders are walked with [DocumentsContract], the format comes
 * from [SourceAudioProbe], the stream details of other formats from the library's [TrackFileInfoReader], and tags,
 * comment and front cover from the converter's [SourceMetadataReader] (JAudioTagger, which reads the ID3 chunk of AIFF
 * files that the platform's metadata retriever cannot open), in one read of the file.
 */
class SafExportSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileInfo: TrackFileInfoReader,
    private val metadata: SourceMetadataReader,
) : ExportSourceReader {

    private val resolver get() = context.contentResolver

    override suspend fun listAudioFiles(treeUri: String): List<String> = withContext(Dispatchers.IO) {
        val tree = treeUri.toUri()
        val found = mutableListOf<Pair<String, String>>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        while (pending.isNotEmpty()) {
            val (folderId, folderPath) = pending.removeFirst()
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("Cannot list $treeUri")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1).orEmpty()
                    val mime = it.getString(2).orEmpty()
                    when {
                        mime == Document.MIME_TYPE_DIR -> pending += id to "$folderPath/$name"
                        isAudio(name, mime) ->
                            found += "$folderPath/$name" to DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    }
                }
            }
        }
        found.sortedBy { it.first.lowercase(Locale.ROOT) }.map { it.second }
    }

    override suspend fun inspect(uri: String): SourceFile = withContext(Dispatchers.IO) {
        val document = uri.toUri()
        val (name, size) = nameAndSize(document) ?: throw IOException("Cannot read $uri")
        val mime = resolver.getType(document)
        val audio = SourceAudioProbe.probe(name, mime) { openInput(uri) }
        SourceFile(uri, name, size, withStreamInfo(audio, uri, name, size, mime))
    }

    /**
     * [audio] of a format the probe only names (FLAC, MP3, ...), completed with the length, rate and channels its
     * metadata states, so the export can tell the size of its conversion. Unchanged when they cannot be read.
     */
    private suspend fun withStreamInfo(audio: SourceAudio, uri: String, name: String, size: Long, mime: String?): SourceAudio {
        if (audio.container in HEADER_READ || audio.container == AudioContainer.UNKNOWN) return audio
        return try {
            val stream = fileInfo.read(trackFor(uri, name, size, mime)).audio
            audio.copy(
                sampleRateHz = audio.sampleRateHz ?: stream.sampleRateHz,
                channelCount = audio.channelCount ?: stream.channelCount,
                durationMs = audio.durationMs ?: stream.durationMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream info of ${redactForLog(uri)} unreadable; its conversion size is estimated from the file size", e)
            audio
        }
    }

    /**
     * The metadata reader gives `null` both for a file without readable tags and for a document it could not open, so
     * then the document is opened once more to tell the two apart: a failure that may pass (the stick or grant gone
     * for a moment) must not pass for a file without tags.
     */
    override suspend fun readTags(uri: String): SourceTags {
        val read = try {
            metadata.read(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tags of ${redactForLog(uri)} unreadable; using the file name", e)
            null
        }
        return ExportTags.orUnreadable(read) { withContext(Dispatchers.IO) { openInput(uri).use { it.read() } } }
    }

    override fun openInput(uri: String): InputStream =
        resolver.openInputStream(uri.toUri()) ?: throw IOException("No stream for $uri")

    private fun nameAndSize(document: Uri): Pair<String, Long>? =
        resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = it.getString(0) ?: document.lastPathSegment?.substringAfterLast('/') ?: "audio"
            name to (if (it.isNull(1)) 0L else it.getLong(1))
        }

    /** The file info reader works on library tracks; a picked document is one that is not indexed. */
    private fun trackFor(uri: String, name: String, size: Long, mimeType: String?) = Track(
        id = 0,
        uri = uri,
        title = name,
        artist = "",
        album = "",
        albumArtist = null,
        durationMs = 0,
        trackNumber = null,
        discNumber = null,
        year = null,
        albumId = 0,
        artworkUri = null,
        mimeType = mimeType ?: "audio/aiff",
        sizeBytes = size,
    )

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

    private companion object {
        const val TAG = "SonoraExport"
        /** Formats whose header the probe reads itself. */
        val HEADER_READ = setOf(AudioContainer.AIFF, AudioContainer.AIFF_C)
        val AUDIO_EXTENSIONS = setOf("aif", "aiff", "aifc", "wav", "flac", "mp3", "m4a", "aac", "ogg", "opus")
    }
}
