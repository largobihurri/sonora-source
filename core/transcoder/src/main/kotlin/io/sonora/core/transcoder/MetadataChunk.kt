package io.sonora.core.transcoder

import io.sonora.core.domain.conversion.ConversionProfile

/**
 * A container chunk carrying metadata, written after the sound data: for AIFF an `ID3 ` chunk holding an ID3v2 tag
 * (tags and artwork), or `NAME` / `AUTH` / `COMT` text chunks.
 */
class MetadataChunk(val id: String, val data: ByteArray) {
    init {
        require(id.length == 4 && id.all { it in ' '..'~' }) { "Chunk ids are four printable ASCII characters: '$id'" }
    }
}

/**
 * The metadata stage: the chunks that carry [sourceUri]'s tags and artwork into an output of
 * [profile]. The engine calls it once per file, after the sound data is written, only for profiles whose
 * [ConversionProfile.metadata] copies tags. Exceptions other than cancellation fail the conversion.
 */
fun interface MetadataChunkSource {
    suspend fun chunksFor(sourceUri: String, profile: ConversionProfile): List<MetadataChunk>

    companion object {
        /** Writes no metadata. */
        val None: MetadataChunkSource = MetadataChunkSource { _, _ -> emptyList() }
    }
}
