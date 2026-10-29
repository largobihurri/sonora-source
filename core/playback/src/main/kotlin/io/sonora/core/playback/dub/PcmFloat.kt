package io.sonora.core.playback.dub

import io.sonora.core.djloop.PcmEncoding
import java.nio.ByteBuffer

/** Reads interleaved PCM of any [PcmEncoding] the DJ engine knows as float samples in -1..1. Allocation-free. */
internal object PcmFloat {

    /** Decodes [count] samples of [encoding] from [input]'s position (which is left as it is) into [out]. */
    fun decode(input: ByteBuffer, encoding: PcmEncoding, out: FloatArray, count: Int) {
        var p = input.position()
        when (encoding) {
            PcmEncoding.PCM_16 -> for (i in 0 until count) {
                out[i] = ((input.get(p + 1).toInt() shl 8) or u(input, p)) * (1f / 32_768)
                p += 2
            }
            PcmEncoding.PCM_16_BE -> for (i in 0 until count) {
                out[i] = ((input.get(p).toInt() shl 8) or u(input, p + 1)) * (1f / 32_768)
                p += 2
            }
            PcmEncoding.PCM_24 -> for (i in 0 until count) {
                out[i] = ((input.get(p + 2).toInt() shl 16) or (u(input, p + 1) shl 8) or u(input, p)) * (1f / 8_388_608)
                p += 3
            }
            PcmEncoding.PCM_24_BE -> for (i in 0 until count) {
                out[i] = ((input.get(p).toInt() shl 16) or (u(input, p + 1) shl 8) or u(input, p + 2)) * (1f / 8_388_608)
                p += 3
            }
            PcmEncoding.PCM_32 -> for (i in 0 until count) {
                out[i] = (int32(input, p, bigEndian = false) * (1.0 / 2_147_483_648.0)).toFloat()
                p += 4
            }
            PcmEncoding.PCM_32_BE -> for (i in 0 until count) {
                out[i] = (int32(input, p, bigEndian = true) * (1.0 / 2_147_483_648.0)).toFloat()
                p += 4
            }
            PcmEncoding.FLOAT_32 -> for (i in 0 until count) {
                out[i] = Float.fromBits(int32(input, p, bigEndian = false))
                p += 4
            }
        }
    }

    /** Writes [count] samples of [samples] into [output] as native-order float, from its position. */
    fun encodeFloat(samples: FloatArray, count: Int, output: ByteBuffer) {
        for (i in 0 until count) output.putFloat(samples[i])
    }

    private fun u(b: ByteBuffer, p: Int): Int = b.get(p).toInt() and 0xFF

    private fun int32(b: ByteBuffer, p: Int, bigEndian: Boolean): Int = if (bigEndian) {
        (b.get(p).toInt() shl 24) or (u(b, p + 1) shl 16) or (u(b, p + 2) shl 8) or u(b, p + 3)
    } else {
        (b.get(p + 3).toInt() shl 24) or (u(b, p + 2) shl 16) or (u(b, p + 1) shl 8) or u(b, p)
    }
}
