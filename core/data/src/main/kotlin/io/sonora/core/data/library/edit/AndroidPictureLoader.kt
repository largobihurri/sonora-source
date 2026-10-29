package io.sonora.core.data.library.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.port.PictureLoader
import io.sonora.core.domain.tags.Picture
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PictureLoader] for images picked with the photo picker. A JPEG or PNG that is already a sensible cover (at most
 * [MAX_SIDE] pixels a side and [MAX_KEPT_BYTES]) is embedded as is; anything else (a camera photo, HEIC, WebP) is
 * decoded with its EXIF rotation applied, scaled to fit [MAX_SIDE] and re-encoded as JPEG, so tags stay small.
 */
class AndroidPictureLoader @Inject constructor(@ApplicationContext private val context: Context) : PictureLoader {

    override suspend fun load(uri: String): Picture? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri.toUri())?.use(::readBounded) ?: return@withContext null
            fit(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Picked image unusable", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Picked image too large to decode", e)
            null
        }
    }

    /** The whole stream, or `null` past [MAX_SOURCE_BYTES] (no picture worth embedding is that large). */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_SOURCE_BYTES) return null
        }
    }

    private fun fit(bytes: ByteArray): Picture {
        var width = 0
        var height = 0
        var mimeType: String? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            mimeType = info.mimeType
            val scale = MAX_SIDE.toFloat() / max(width, height)
            if (scale < 1f) decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (mimeType in KEPT_TYPES && max(width, height) <= MAX_SIDE && bytes.size <= MAX_KEPT_BYTES) {
            bitmap.recycle()
            return Picture(bytes, mimeType, width, height)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Picture(out.toByteArray(), "image/jpeg", bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    private companion object {
        const val TAG = "SonoraTagEdit"
        const val MAX_SIDE = 1600
        const val MAX_KEPT_BYTES = 2 * 1024 * 1024
        const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
        const val JPEG_QUALITY = 90
        val KEPT_TYPES = setOf("image/jpeg", "image/png")
    }
}
