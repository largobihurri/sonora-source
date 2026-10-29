package io.sonora.core.data.conversion

import android.util.Log
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.TrackMetadata
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import org.jaudiotagger.audio.AudioFileIO

/**
 * Reads a source's [TrackMetadata] with JAudioTagger under the shared [JAudioTagger] lock, from the document's own
 * path when it has a readable one, else from a temporary copy in [tempDir] (the library scanner's strategy). The
 * front cover goes through [prepareCover] outside the lock; a cover that cannot be prepared costs only the cover.
 */
internal class SourceMetadataFileReader(
    private val tempDir: File,
    private val prepareCover: (ByteArray) -> CoverArt?,
    /** The file's metadata and front cover bytes, `null` when it has no tag; JAudioTagger by default. */
    private val readTags: (file: File, readerKey: String) -> Pair<TrackMetadata, ByteArray?>? = ::readWithJAudioTagger,
) {
    /**
     * Metadata of a file JAudioTagger reads with [readerKey] (`mp3`, `flac`, `m4a`, `wav`, `aiff`, …); `null` when
     * the tags cannot be read. [direct] is the readable path, else [copyTo] writes the document to a temporary file.
     */
    fun read(readerKey: String, direct: File?, copyTo: (OutputStream) -> Unit): TrackMetadata? {
        if (direct != null) return readFile(direct, readerKey)
        val copy = try {
            File.createTempFile("tags", ".$readerKey", tempDir)
        } catch (e: Exception) {
            Log.w(TAG, "No copy can be made to read tags from; converting without tags", e)
            return null
        }
        // Deleted on every path, a copy that breaks part way (tens of MB for an AIFF) included.
        try {
            try {
                copy.outputStream().use(copyTo)
            } catch (e: Exception) {
                Log.w(TAG, "Source cannot be copied for tag reading; converting without tags", e)
                return null
            }
            return readFile(copy, readerKey)
        } finally {
            copy.delete()
        }
    }

    private fun readFile(file: File, readerKey: String): TrackMetadata? {
        val (metadata, coverBytes) = try {
            readTags(file, readerKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tags of this $readerKey source are unreadable; converting without them", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "Tag reader unavailable; converting without tags", e)
            null
        } catch (e: OutOfMemoryError) {
            // A tag declaring a huge field (an embedded picture, as a rule) fails its one allocation, which never
            // happened, so the heap is as it was and the conversion can go on untagged. Other VM errors still escape.
            Log.w(TAG, "Tags of this $readerKey source are too large to read; converting without them", e)
            null
        } ?: return null
        val cover = coverBytes?.let { bytes ->
            try {
                prepareCover(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Cover unreadable; converting without it", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Cover too large to re-encode; converting without it", e)
                null
            }
        }
        return metadata.copy(cover = cover)
    }

    private companion object {
        const val TAG = "SonoraMetadata"
    }
}

private fun readWithJAudioTagger(file: File, readerKey: String): Pair<TrackMetadata, ByteArray?>? = JAudioTagger.read {
    val tag = AudioFileIO.readAs(file, readerKey).tag ?: return@read null
    SourceMetadataMapper.map(tag) to SourceMetadataMapper.frontCover(tag)
}
