package io.sonora.core.domain.conversion

import java.util.Locale

/**
 * Before a batch that overwrites originals starts: how many files would replace a lossless original (FLAC, WAV,
 * AIFF, ALAC, APE, WavPack...) with a lossy file (AAC, Opus, MP3), so the user can confirm first.
 */
object LossyOverwriteCheck {

    /** One file of the batch: its source's name, the codec the library read (if known), and its resolved output. */
    data class Item(val sourceName: String, val sourceCodec: String?, val target: ConversionProfile)

    /** Lossless codecs, as the library's scanner names them. */
    private val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "APE", "WAVPACK", "TTA", "DSD")

    /**
     * Lossless file extensions. An M4A is not among them: without its codec it is most often AAC, and an ALAC one is
     * found by its codec.
     */
    private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "wave", "aif", "aiff", "aifc", "ape", "wv", "tta", "dsf", "dff")

    /** Files that would replace a lossless original with a lossy output; 0 while [overwriteOriginals] is off. */
    fun count(items: List<Item>, overwriteOriginals: Boolean): Int {
        if (!overwriteOriginals) return 0
        return items.count { it.target.isLossy && isLosslessSource(it.sourceName, it.sourceCodec) }
    }

    /** Whether the source is lossless: by [codec] when the library read one, else by the extension of [name]. */
    fun isLosslessSource(name: String, codec: String?): Boolean {
        if (codec != null) return codec.uppercase(Locale.ROOT) in LOSSLESS_CODECS
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in LOSSLESS_EXTENSIONS
    }
}
