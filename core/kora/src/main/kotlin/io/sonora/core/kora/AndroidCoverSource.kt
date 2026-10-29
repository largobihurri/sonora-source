package io.sonora.core.kora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Reads the cover at a library artwork URI (a cached file or a content URI, whatever the UI loads) with a bounds pass
 * and a sampled decode, so a large cover is never held at full size. Any failure to open or decode reads as no cover.
 */
internal class AndroidCoverSource @Inject constructor(@ApplicationContext private val context: Context) : CoverSource {
    override fun open(uri: String, maxEdgePx: Int): CoverImage? = runCatching {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        }
        val bitmap = context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
        bitmap?.let(::BitmapCover)
    }.getOrNull()

    private class BitmapCover(private val bitmap: Bitmap) : CoverImage {
        override val widthPx: Int = bitmap.width
        override val heightPx: Int = bitmap.height

        override fun jpeg(widthPx: Int, heightPx: Int, quality: Int): ByteArray {
            val scaled = if (widthPx == bitmap.width && heightPx == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, widthPx, heightPx, true)
            // JPEG has no alpha: a transparent cover is laid on white rather than turning black.
            val flat = if (scaled.hasAlpha()) {
                Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                }
            } else {
                scaled
            }
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (flat !== scaled) flat.recycle()
            if (scaled !== bitmap) scaled.recycle()
            return out.toByteArray()
        }
    }

    internal companion object {
        /** The largest power of two that keeps the longer edge at [maxEdgePx] or more (the original size if it is smaller). */
        fun sampleSize(widthPx: Int, heightPx: Int, maxEdgePx: Int): Int {
            var sample = 1
            while (maxOf(widthPx, heightPx) / (sample * 2) >= maxEdgePx) sample *= 2
            return sample
        }
    }
}
