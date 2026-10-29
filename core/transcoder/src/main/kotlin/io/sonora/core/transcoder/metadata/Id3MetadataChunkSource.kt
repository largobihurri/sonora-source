package io.sonora.core.transcoder.metadata

import android.util.Log
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.wav.RiffInfo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Carries a source's tags and front cover into the containers that hold an ID3v2.3 tag ([Id3v23Writer], with `TSSE`
 * naming Sonora and the profile): AIFF as one `ID3 ` chunk; WAV as a `LIST` `INFO` chunk with the basic fields
 * ([RiffInfo]) followed by an `id3 ` chunk with all of them and the cover; MP3 as one [MP3_ID3_CHUNK] chunk whose
 * tag the encoder writes ahead of the audio. Sources whose tags cannot be read, or that
 * have none, get no chunk: the conversion still succeeds, untagged.
 */
class Id3MetadataChunkSource @Inject constructor(private val reader: SourceMetadataReader) : MetadataChunkSource {

    override suspend fun chunksFor(sourceUri: String, profile: ConversionProfile): List<MetadataChunk> {
        // Exhaustive on purpose: a new container must decide how it stores tags.
        when (profile.container) {
            OutputContainer.AIFF, OutputContainer.WAV, OutputContainer.MP3 -> Unit
            // These carry Vorbis comments or MP4 atoms instead: OpusTags, Flac and Mp4MetadataChunkSource.
            OutputContainer.OGG_OPUS, OutputContainer.FLAC, OutputContainer.M4A -> return emptyList()
        }
        val metadata = try {
            reader.read(sourceUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tags of ${redactForLog(sourceUri)} unreadable; converting without them", e)
            null
        }
        if (metadata == null || metadata.isEmpty) return emptyList()
        val id3 = Id3v23Writer.write(metadata, encoderName(profile))
        return when (profile.container) {
            OutputContainer.WAV -> listOf(
                MetadataChunk(RiffInfo.CHUNK_ID, RiffInfo.body(metadata, encoderName(profile))),
                MetadataChunk(WAV_ID3_CHUNK, id3),
            )
            OutputContainer.MP3 -> listOf(MetadataChunk(MP3_ID3_CHUNK, id3))
            else -> listOf(MetadataChunk(AIFF_ID3_CHUNK, id3))
        }
    }

    companion object {
        private const val TAG = "SonoraMetadata"
        const val AIFF_ID3_CHUNK = "ID3 "

        /** The lower-case id most WAV taggers write and read (JAudioTagger, foobar2000, Mp3tag). */
        const val WAV_ID3_CHUNK = "id3 "

        /** Id of the chunk an MP3 encoder writes at the start of the file: the bare tag. */
        const val MP3_ID3_CHUNK = "ID3 "

        /** The `TSSE` value of converted files, e.g. "Sonora (Pioneer XDJ-700)". */
        fun encoderName(profile: ConversionProfile): String = "Sonora (${profile.displayName})"
    }
}
