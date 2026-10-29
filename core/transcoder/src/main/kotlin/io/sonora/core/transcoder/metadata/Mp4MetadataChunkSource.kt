package io.sonora.core.transcoder.metadata

import android.util.Log
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.AacM4aEncoder
import io.sonora.core.transcoder.mp4.Mp4Ilst
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Carries a source's tags and front cover into M4A outputs as one [AacM4aEncoder.ILST_CHUNK_ID] chunk holding the
 * complete iTunes `ilst` box ([Mp4Ilst], `©too` naming Sonora and the profile); the encoder puts it into
 * `moov/udta/meta` once the muxer is done. Sources whose tags cannot be read, or that have none, get no chunk: the
 * file then carries no tags and the conversion still succeeds.
 */
class Mp4MetadataChunkSource @Inject constructor(private val reader: SourceMetadataReader) : MetadataChunkSource {

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
        return listOf(MetadataChunk(AacM4aEncoder.ILST_CHUNK_ID, Mp4Ilst.build(metadata, Id3MetadataChunkSource.encoderName(profile))))
    }

    private companion object {
        const val TAG = "SonoraMetadata"
    }
}
