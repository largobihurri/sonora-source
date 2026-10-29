package io.sonora.core.kora

import kotlin.math.roundToInt

/** A decoded cover, [widthPx] by [heightPx], that can be re-encoded as a JPEG at another size and quality. */
internal interface CoverImage {
    val widthPx: Int
    val heightPx: Int

    /** The image scaled to exactly [widthPx] by [heightPx] and JPEG-encoded at [quality] (1..100). */
    fun jpeg(widthPx: Int, heightPx: Int, quality: Int): ByteArray
}

/** Where Sonora's covers come from: decodes the cover at [uri], sampled down while decoding. */
internal interface CoverSource {
    /** The cover decoded no larger than needed for [maxEdgePx] (never smaller than it, unless the original is), or null if undecodable. */
    fun open(uri: String, maxEdgePx: Int): CoverImage?
}

/** A JPEG that fits the cap, with the size it really has. */
internal class EncodedCover(val bytes: ByteArray, val widthPx: Int, val heightPx: Int)

/** The sizing and step-down of cover art for `media.artwork`: pure, so it is tested without Android. */
internal object ArtworkSizing {
    const val START_QUALITY = 85
    val QUALITY_STEPS: List<Int> = listOf(START_QUALITY, 75, 65, 55, 45)

    /** The size of an image whose longer edge is [edgePx]: the aspect ratio kept, each side at least 1 px. */
    fun scaledSize(widthPx: Int, heightPx: Int, edgePx: Int): Pair<Int, Int> {
        val longer = maxOf(widthPx, heightPx)
        fun side(value: Int) = (value.toLong() * edgePx / longer.toDouble()).roundToInt().coerceAtLeast(1)
        return side(widthPx) to side(heightPx)
    }

    /** The longer edge to start from: what was asked, but never above the original (a cover is not upscaled). */
    fun startEdge(widthPx: Int, heightPx: Int, requestedEdgePx: Int): Int = minOf(requestedEdgePx, maxOf(widthPx, heightPx))

    /**
     * Encodes [image] as a JPEG of at most [capBytes]: at the start edge it lowers the quality step by step; if even the
     * lowest does not fit, the edge shrinks by a quarter and the quality starts again from [START_QUALITY].
     */
    fun encodeUnderCap(image: CoverImage, requestedEdgePx: Int, capBytes: Int): EncodedCover {
        var edge = startEdge(image.widthPx, image.heightPx, requestedEdgePx)
        while (true) {
            val (width, height) = scaledSize(image.widthPx, image.heightPx, edge)
            for (quality in QUALITY_STEPS) {
                val bytes = image.jpeg(width, height, quality)
                if (bytes.size <= capBytes) return EncodedCover(bytes, width, height)
            }
            // Nothing this small can fail to fit; the guard only keeps a hopeless encoder from looping forever.
            check(edge > MIN_EDGE_PX) { "A cover does not fit the cap even at $MIN_EDGE_PX px" }
            edge = maxOf(MIN_EDGE_PX, edge * 3 / 4)
        }
    }

    private const val MIN_EDGE_PX = 16
}
