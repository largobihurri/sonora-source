package io.sonora.core.data.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.CoverArtRule
import java.io.ByteArrayOutputStream

/**
 * Applies [CoverArtRule] with Android's image codecs: small JPEG/PNG covers are kept byte for byte, anything else that
 * decodes is scaled to fit the re-encode box and stored as an opaque JPEG (transparent areas on white). Pictures that
 * do not decode give `null`.
 */
internal object AndroidCoverArt {

    fun prepare(bytes: ByteArray): CoverArt? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val mimeType = CoverArtRule.mimeTypeOf(bytes)
        if (mimeType != null && CoverArtRule.keepsOriginal(mimeType, width, height, bytes.size)) {
            return CoverArt(bytes, mimeType, width, height)
        }
        val (targetWidth, targetHeight) = CoverArtRule.scaledSize(width, height)
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(width, height, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val target = createBitmap(targetWidth, targetHeight)
        try {
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, null, Rect(0, 0, targetWidth, targetHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val out = ByteArrayOutputStream()
            if (!target.compress(Bitmap.CompressFormat.JPEG, CoverArtRule.REENCODE_JPEG_QUALITY, out)) return null
            return CoverArt(out.toByteArray(), CoverArtRule.JPEG, targetWidth, targetHeight)
        } finally {
            decoded.recycle()
            target.recycle()
        }
    }

    /** Largest power-of-two subsampling that still decodes at least the target size (bounds decode memory). */
    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }
}
