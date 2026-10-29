package io.sonora.core.transcoder.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.CoverArtRule
import io.sonora.core.transcoder.metadata.CoverScaler
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * [CoverScaler] on the platform's bitmap codecs: decodes at a reduced sample size so a large cover never sits in
 * memory whole, scales it to fit, flattens transparency onto white and writes a JPEG.
 */
class AndroidCoverScaler @Inject constructor() : CoverScaler {

    override fun scale(cover: CoverArt, maxSidePx: Int): CoverArt? {
        val bytes = cover.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        val ratio = minOf(1.0, maxSidePx.toDouble() / longest)
        val width = maxOf(1, Math.round(decoded.width * ratio).toInt())
        val height = maxOf(1, Math.round(decoded.height * ratio).toInt())
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, null, android.graphics.Rect(0, 0, width, height), null)
            }
            val out = ByteArrayOutputStream()
            if (!flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            return CoverArt(out.toByteArray(), CoverArtRule.JPEG, width, height)
        } finally {
            flat.recycle()
            decoded.recycle()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
