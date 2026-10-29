package io.sonora.core.transcoder.metadata

import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import javax.inject.Inject

/**
 * Picks the tag format of the profile's container: ID3v2.3 for AIFF, WAV (with RIFF INFO) and MP3, Vorbis comments
 * for Ogg Opus and FLAC (with a PICTURE block), an iTunes `ilst` for M4A. Every format carries tags and the cover.
 */
class ContainerMetadataChunkSource @Inject constructor(
    private val id3: Id3MetadataChunkSource,
    private val opus: OpusTagsMetadataChunkSource,
    private val flac: FlacMetadataChunkSource,
    private val mp4: Mp4MetadataChunkSource,
) : MetadataChunkSource {

    override suspend fun chunksFor(sourceUri: String, profile: ConversionProfile): List<MetadataChunk> =
        // Exhaustive on purpose: a new container must decide how it stores tags.
        when (profile.container) {
            OutputContainer.AIFF, OutputContainer.WAV, OutputContainer.MP3 -> id3.chunksFor(sourceUri, profile)
            OutputContainer.OGG_OPUS -> opus.chunksFor(sourceUri, profile)
            OutputContainer.FLAC -> flac.chunksFor(sourceUri, profile)
            OutputContainer.M4A -> mp4.chunksFor(sourceUri, profile)
        }
}
