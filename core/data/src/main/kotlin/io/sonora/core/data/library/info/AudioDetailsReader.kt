package io.sonora.core.data.library.info

import android.util.Log
import io.sonora.core.data.library.tags.JAudioTagger
import io.sonora.core.domain.audio.AiffHeader
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.TagInfo
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import org.jaudiotagger.audio.AudioFileIO

/**
 * Stream and tag details of one audio file. [audio] and [tags] are `null` when JAudioTagger cannot read the file;
 * [tags] is an empty [TagInfo] when it reads the file and finds no tag. [aiff] is the shared parser's AIFF header.
 */
internal class AudioDetails(val aiff: AiffHeader?, val audio: AudioStreamInfo?, val tags: TagInfo?)

/**
 * Reads [AudioDetails] with JAudioTagger (through the shared [JAudioTagger] lock), from the document's own path when
 * there is one, else from a temporary copy in [tempDir], like the library scanner's tag reader.
 */
internal class AudioDetailsReader(
    private val tempDir: File,
    private val imageSize: (ByteArray) -> Pair<Int, Int>?,
) {
    /**
     * Details of a file JAudioTagger reads with [readerKey]. [direct] is the document's readable path; without one
     * (under scoped storage a SAF descriptor usually cannot be reopened by its `/proc/self/fd` path) [copyTo] writes
     * the document to a temporary file that is read instead and deleted afterwards. A failing copy throws: the file
     * itself is unreadable, which is not the same as unavailable tags.
     */
    fun read(readerKey: String, direct: File?, copyTo: (OutputStream) -> Unit): AudioDetails {
        if (direct != null) return readFile(direct, readerKey)
        val copy = File.createTempFile("info", ".$readerKey", tempDir)
        try {
            copy.outputStream().use(copyTo)
            return readFile(copy, readerKey)
        } finally {
            copy.delete()
        }
    }

    private fun readFile(file: File, readerKey: String): AudioDetails {
        val aiff = if (readerKey in AIFF_KEYS) parseAiff(file) else null
        return try {
            JAudioTagger.read {
                val audioFile = AudioFileIO.readAs(file, readerKey)
                val tag = audioFile.tag
                val audio = FileInfoMapper.audio(RawAudioHeader.of(audioFile.audioHeader), readerKey, aiff)
                AudioDetails(
                    aiff = aiff,
                    audio = audio.copy(encoder = audio.encoder ?: tag?.let(FileInfoMapper::tagEncoder)),
                    tags = tag?.let { FileInfoMapper.tags(it, imageSize) } ?: TagInfo(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "File info: JAudioTagger cannot read this $readerKey file; tags shown as unavailable", e)
            AudioDetails(aiff, null, null)
        } catch (e: LinkageError) {
            Log.w(TAG, "File info: tag reader unavailable", e)
            AudioDetails(aiff, null, null)
        }
    }

    private fun parseAiff(file: File): AiffHeader? = try {
        file.inputStream().buffered().use { AiffParser.parse(AiffSource.of(it)) }
    } catch (e: Exception) {
        Log.w(TAG, "File info: AIFF header unreadable", e)
        null
    }

    companion object {
        private const val TAG = "SonoraFileInfo"
        val AIFF_KEYS = setOf("aif", "aiff", "aifc")
    }
}
