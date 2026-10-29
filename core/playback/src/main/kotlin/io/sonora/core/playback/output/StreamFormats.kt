package io.sonora.core.playback.output

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.core.domain.playback.output.SourcePcm
import io.sonora.core.domain.playback.output.StreamFormat

/** Media3's formats and PCM encodings as the bit-perfect planner's types. */
@UnstableApi
internal object StreamFormats {
    /** The stream a raw PCM [format] carries, or `null` for anything else (compressed audio, a format with no rate or channels). */
    fun of(format: Format): StreamFormat? {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
        if (format.sampleRate <= 0 || format.channelCount <= 0) return null
        val pcm = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> SourcePcm.INT8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> SourcePcm.INT16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> SourcePcm.INT24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> SourcePcm.INT32
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> SourcePcm.FLOAT32
            else -> return null
        }
        return StreamFormat(format.sampleRate, format.channelCount, pcm)
    }

    /** The Media3 PCM encoding of an AudioTrack in [encoding] (Media3 and Android share the numbers). */
    fun media3Encoding(encoding: OutputEncoding): Int = when (encoding) {
        OutputEncoding.PCM_8 -> C.ENCODING_PCM_8BIT
        OutputEncoding.PCM_16 -> C.ENCODING_PCM_16BIT
        OutputEncoding.PCM_24_PACKED -> C.ENCODING_PCM_24BIT
        OutputEncoding.PCM_32 -> C.ENCODING_PCM_32BIT
        OutputEncoding.PCM_FLOAT -> C.ENCODING_PCM_FLOAT
        OutputEncoding.OTHER -> C.ENCODING_INVALID
    }
}
