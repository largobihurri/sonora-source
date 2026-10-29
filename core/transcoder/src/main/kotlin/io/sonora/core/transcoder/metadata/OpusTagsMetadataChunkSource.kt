package io.sonora.core.transcoder.metadata

import android.util.Log
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.OpusOggEncoder
import io.sonora.core.transcoder.ogg.OpusHeaders
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Carries a source's tags and front cover into Ogg Opus outputs as one [OpusOggEncoder.TAGS_CHUNK_ID] chunk holding
 * the complete `OpusTags` packet ([OpusTagComments] behind [OpusHeaders.tags]); the encoder writes it as the second
 * header packet, ahead of the audio. Sources whose tags cannot be read, or that have none, get no chunk: the encoder
 * then writes empty tags and the conversion still succeeds.
 */
class OpusTagsMetadataChunkSource @Inject constructor(
    private val reader: SourceMetadataReader,
    private val coverScaler: CoverScaler,
) : MetadataChunkSource {

    override suspend fun chunksFor(sourceUri: String, profile: ConversionProfile): List<MetadataChunk> {
        val metadata = try {
            reader.read(sourceUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tags of ${redactForLog(sourceUri)} unreadable; converting without them", e)
            null
        }
        if (metadata == null || metadata.isEmpty) return emptyList()
        val packet = OpusHeaders.tags(Id3MetadataChunkSource.encoderName(profile), OpusTagComments.from(metadata, coverScaler))
        return listOf(MetadataChunk(OpusOggEncoder.TAGS_CHUNK_ID, packet))
    }

    private companion object {
        const val TAG = "SonoraMetadata"
    }
}
