package io.sonora.core.data.library.tags

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.saf.AudioFiles
import io.sonora.core.data.library.saf.ListedAudioFile
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.log.redactForLog
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag

/** Tags of one file plus its cached embedded picture. */
data class TagReadResult(val tags: TrackTags, val embeddedArtworkUri: String?) {
    companion object {
        val UNTAGGED = TagReadResult(TrackTags.UNTAGGED, null)
    }
}

/** Reads one audio file's tags. */
fun interface TagReader {
    /**
     * Tags of [file]; [TagReadResult.UNTAGGED] when it was read but carries no usable tags, `null` when it could
     * not be opened or read right now (access revoked, provider or I/O error), so the caller retries it later; the
     * scanner bounds those retries and then indexes the file untagged.
     */
    fun read(file: ListedAudioFile): TagReadResult?
}

/**
 * Reads tags with JAudioTagger 3.0.1 from SAF documents without copying them: the document is opened as a
 * file descriptor and handed to [AudioFileIO.readAs] as `/proc/self/fd/N` with the reader chosen from the
 * name or MIME type (`readAs` skips the extension check that `read` performs). Only when that path is not
 * readable is the file copied to a temporary cache file. A damaged file yields [TagReadResult.UNTAGGED] and never
 * stops a scan; a document that cannot be opened or read right now ([ReadFailures.isTransient]) yields `null`, so
 * the scanner retries it. Reads go through the shared [JAudioTagger] lock.
 */
@Singleton
class JAudioTaggerTagReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artworkCache: ArtworkCache,
) : TagReader {
    override fun read(file: ListedAudioFile): TagReadResult? {
        val readerKey = AudioFiles.readerExtension(file.fileName, file.mimeType) ?: return TagReadResult.UNTAGGED
        val descriptor = try {
            context.contentResolver.openFileDescriptor(file.documentUri.toUri(), "r")
        } catch (e: Exception) {
            Log.d(TAG, "Cannot open ${file.fileName} now; retrying next scan", e)
            null
        } ?: return null
        return descriptor.use {
            try {
                read(it, readerKey)
            } catch (e: Exception) {
                if (ReadFailures.isTransient(e)) {
                    Log.d(TAG, "Cannot read ${file.fileName} now; retrying next scan", e)
                    null
                } else {
                    Log.d(TAG, "Tags unreadable, using fallbacks: ${file.fileName}", e)
                    TagReadResult.UNTAGGED
                }
            } catch (e: LinkageError) {
                Log.w(TAG, "Tag reader unavailable for ${redactForLog(file.fileName)}", e)
                TagReadResult.UNTAGGED
            }
        }
    }

    private fun read(descriptor: ParcelFileDescriptor, readerKey: String): TagReadResult {
        val path = File("/proc/self/fd/${descriptor.fd}")
        val audio = if (path.canRead()) {
            JAudioTagger.read { AudioFileIO.readAs(path, readerKey) }
        } else {
            readCopy(descriptor, readerKey)
        }
        return toResult(audio, readerKey)
    }

    /** Last resort for providers whose descriptors cannot be reopened by path (e.g. pipes). */
    private fun readCopy(descriptor: ParcelFileDescriptor, readerKey: String): AudioFile {
        val copy = File.createTempFile("tags", ".$readerKey", context.cacheDir)
        try {
            FileInputStream(descriptor.fileDescriptor).use { input -> copy.outputStream().use { input.copyTo(it) } }
            return JAudioTagger.read { AudioFileIO.readAs(copy, readerKey) }
        } finally {
            copy.delete()
        }
    }

    private fun toResult(audio: AudioFile, readerKey: String): TagReadResult {
        val tag: Tag? = audio.tag
        val header = audio.audioHeader
        val raw = RawTags(
            title = tag.field(FieldKey.TITLE),
            artist = tag.field(FieldKey.ARTIST),
            album = tag.field(FieldKey.ALBUM),
            albumArtist = tag.field(FieldKey.ALBUM_ARTIST),
            genre = tag.field(FieldKey.GENRE),
            year = tag.field(FieldKey.YEAR),
            track = tag.field(FieldKey.TRACK),
            disc = tag.field(FieldKey.DISC_NO),
            compilation = tag.field(FieldKey.IS_COMPILATION),
            durationSec = header?.let { runCatching { it.preciseTrackLength }.getOrNull() },
            sampleRate = header?.let { runCatching { it.sampleRateAsNumber }.getOrNull() },
            bitsPerSample = header?.let { runCatching { it.bitsPerSample }.getOrNull() },
            channels = header?.let { runCatching { it.channels }.getOrNull() },
            readerKey = readerKey,
            encodingType = header?.let { runCatching { it.encodingType }.getOrNull() },
            format = header?.let { runCatching { it.format }.getOrNull() },
            lossless = header?.let { runCatching { it.isLossless }.getOrNull() },
            bitrateKbps = header?.let { runCatching { it.bitRateAsNumber }.getOrNull() },
            copyright = tag.field(FieldKey.COPYRIGHT),
        )
        // A broken picture or a full cache costs the artwork only, never the tags.
        val artwork = runCatching {
            tag?.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }?.let(artworkCache::store)
        }.getOrNull()
        return TagReadResult(TagMapper.map(raw), artwork)
    }

    /** One field, `null` when the format has no such field or it is unreadable. */
    private fun Tag?.field(key: FieldKey): String? = this?.let { runCatching { it.getFirst(key) }.getOrNull() }

    private companion object {
        const val TAG = "SonoraTags"
    }
}
