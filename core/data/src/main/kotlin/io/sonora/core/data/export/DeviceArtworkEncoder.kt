package io.sonora.core.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import io.sonora.core.domain.export.DeviceArtworkImages
import java.io.ByteArrayOutputStream

/**
 * A cover as the players read it, with Android's image codecs: the centre square of the picture (rekordbox's pictures
 * are square; cropping keeps a wide cover from being squashed) scaled to 80 and 240 pixels and stored as opaque JPEGs
 * (transparent areas on white). The same picture always gives the same bytes.
 */
internal object DeviceArtworkEncoder {
    const val SMALL_PIXELS = 80
    const val LARGE_PIXELS = 240
    private const val JPEG_QUALITY = 90

    /** [picture] as the two JPEGs; `null` when it does not decode. */
    fun encode(picture: ByteArray): DeviceArtworkImages? {
        if (picture.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(
            picture, 0, picture.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(minOf(width, height))
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        try {
            val side = minOf(decoded.width, decoded.height)
            val left = (decoded.width - side) / 2
            val top = (decoded.height - side) / 2
            val square = Rect(left, top, left + side, top + side)
            return DeviceArtworkImages(
                small = jpeg(decoded, square, SMALL_PIXELS) ?: return null,
                large = jpeg(decoded, square, LARGE_PIXELS) ?: return null,
            )
        } finally {
            decoded.recycle()
        }
    }

    private fun jpeg(source: Bitmap, square: Rect, pixels: Int): ByteArray? {
        val target = createBitmap(pixels, pixels)
        try {
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, square, Rect(0, 0, pixels, pixels), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val out = ByteArrayOutputStream()
            return if (target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
        } finally {
            target.recycle()
        }
    }

    /** Largest power-of-two subsampling that still decodes the short side at [LARGE_PIXELS] or more. */
    private fun sampleSize(shortSide: Int): Int {
        var sample = 1
        while (shortSide / (sample * 2) >= LARGE_PIXELS) sample *= 2
        return sample
    }
}
