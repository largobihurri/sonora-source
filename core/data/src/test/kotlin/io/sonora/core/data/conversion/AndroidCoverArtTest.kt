package io.sonora.core.data.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArtRule
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cover rule on real (native) image codecs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidCoverArtTest {

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat, quality: Int = 95): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height step 7) for (x in 0 until width step 5) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun `a small JPEG and a small PNG are kept byte for byte with their own MIME type`() {
        val jpeg = image(800, 800, Bitmap.CompressFormat.JPEG)
        val png = image(300, 200, Bitmap.CompressFormat.PNG)

        val keptJpeg = AndroidCoverArt.prepare(jpeg)!!
        val keptPng = AndroidCoverArt.prepare(png)!!

        assertThat(keptJpeg.bytes).isEqualTo(jpeg)
        assertThat(keptJpeg.mimeType).isEqualTo("image/jpeg")
        assertThat(keptJpeg.width to keptJpeg.height).isEqualTo(800 to 800)
        assertThat(keptPng.bytes).isEqualTo(png)
        assertThat(keptPng.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `a cover above 1500 pixels is re-encoded as a JPEG that fits 1000 x 1000`() {
        val big = image(2000, 1600, Bitmap.CompressFormat.PNG)

        val cover = AndroidCoverArt.prepare(big)!!

        assertThat(cover.mimeType).isEqualTo(CoverArtRule.JPEG)
        assertThat(CoverArtRule.mimeTypeOf(cover.bytes)).isEqualTo(CoverArtRule.JPEG)
        assertThat(cover.width to cover.height).isEqualTo(1000 to 800)
        assertThat(size(cover.bytes)).isEqualTo(1000 to 800)
    }

    @Test
    fun `a picture in another format is re-encoded as JPEG at its own size`() {
        val webp = image(400, 400, Bitmap.CompressFormat.WEBP_LOSSLESS)

        val cover = AndroidCoverArt.prepare(webp)!!

        assertThat(cover.mimeType).isEqualTo(CoverArtRule.JPEG)
        assertThat(size(cover.bytes)).isEqualTo(400 to 400)
    }

    @Test
    fun `bytes that are not an image give no cover`() {
        assertThat(AndroidCoverArt.prepare(ByteArray(64) { 1 })).isNull()
    }
}
