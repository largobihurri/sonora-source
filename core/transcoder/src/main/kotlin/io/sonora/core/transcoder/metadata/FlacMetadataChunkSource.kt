package io.sonora.core.transcoder.metadata

import android.util.Log
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.FlacEncoder
import io.sonora.core.transcoder.flac.FlacStreamWriter
import io.sonora.core.transcoder.ogg.FlacPicture
import io.sonora.core.transcoder.ogg.OpusHeaders
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Carries a source's tags and front cover into FLAC outputs: the `VORBIS_COMMENT` block body ([FlacTagComments],
 * vendor naming Sonora and the profile) and, when there is a cover, the `PICTURE` block body at full size; the
 * [FlacEncoder] writes both ahead of the frames. Sources whose tags cannot be read, or that have none, get no chunk:
 * the encoder then writes tags holding only the vendor, and the conversion still succeeds.
 */
class FlacMetadataChunkSource @Inject constructor(private val reader: SourceMetadataReader) : MetadataChunkSource {

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
        val comments = OpusHeaders.comments(Id3MetadataChunkSource.encoderName(profile), FlacTagComments.from(metadata))
        val picture = metadata.cover
            ?.let { FlacPicture.block(it.bytes, it.mimeType, it.width, it.height) }
            ?.takeIf { it.size <= FlacStreamWriter.MAX_BLOCK_BYTES }
        return listOfNotNull(
            MetadataChunk(FlacEncoder.VORBIS_COMMENT_CHUNK_ID, comments),
            picture?.let { MetadataChunk(FlacEncoder.PICTURE_CHUNK_ID, it) },
        )
    }

    private companion object {
        const val TAG = "SonoraMetadata"
    }
}
