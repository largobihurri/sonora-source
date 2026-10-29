package io.sonora.core.playback.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import io.sonora.core.domain.playback.ReplayGainTags

/**
 * A stream's ReplayGain tags, from the metadata Media3's extractors attach to its format (which the audio renderer
 * hands on to the sink): Vorbis comments (FLAC, Ogg Vorbis, Opus), ID3 `TXXX` frames (MP3, and AIFF or WAV only if
 * their extractor reads ID3) and MP4 `----` atoms. Other entries are ignored.
 */
@OptIn(UnstableApi::class)
object ReplayGainMetadata {
    fun tags(metadata: Metadata?): ReplayGainTags {
        if (metadata == null || metadata.length() == 0) return ReplayGainTags.NONE
        val fields = ArrayList<Pair<String, String>>()
        for (i in 0 until metadata.length()) {
            when (val entry = metadata[i]) {
                is VorbisComment -> fields += entry.key to entry.value
                is TextInformationFrame -> if (entry.id == "TXXX" && entry.description != null) {
                    entry.values.firstOrNull()?.let { fields += entry.description!! to it }
                }
                is InternalFrame -> fields += entry.description to entry.text
            }
        }
        return ReplayGainTags.fromFields(fields)
    }
}
