package io.sonora.core.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The players' two cover JPEGs, made with real (native) image codecs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceArtworkEncoderTest {

    /** [width] x [height]: the left third red, the middle green, the right third blue. */
    private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, if (x < width / 3) Color.RED else if (x < 2 * width / 3) Color.GREEN else Color.BLUE)
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, 95, it) }.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun isJpeg(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    @Test
    fun `a cover becomes an 80 pixel and a 240 pixel square JPEG`() {
        val images = DeviceArtworkEncoder.encode(picture(1000, 1000, Bitmap.CompressFormat.JPEG))!!

        assertThat(isJpeg(images.small)).isTrue()
        assertThat(isJpeg(images.large)).isTrue()
        assertThat(decode(images.small).let { it.width to it.height }).isEqualTo(80 to 80)
        assertThat(decode(images.large).let { it.width to it.height }).isEqualTo(240 to 240)
    }

    @Test
    fun `a wide cover is cropped square from its centre, never stretched`() {
        // 900 x 300: the centre square is the green third.
        val images = DeviceArtworkEncoder.encode(picture(900, 300))!!

        val large = decode(images.large)
        for ((x, y) in listOf(2 to 2, 120 to 120, 237 to 237)) {
            val pixel = large.getPixel(x, y)
            assertThat(Color.green(pixel)).isGreaterThan(200)
            assertThat(Color.red(pixel)).isLessThan(60)
            assertThat(Color.blue(pixel)).isLessThan(60)
        }
    }

    @Test
    fun `a small cover is scaled up and the same picture always gives the same bytes`() {
        val small = picture(50, 40)

        val first = DeviceArtworkEncoder.encode(small)!!

        assertThat(decode(first.large).width).isEqualTo(240)
        assertThat(DeviceArtworkEncoder.encode(small)).isEqualTo(first)
    }

    @Test
    fun `bytes that are no picture give no images`() {
        assertThat(DeviceArtworkEncoder.encode(ByteArray(300) { 7 })).isNull()
        assertThat(DeviceArtworkEncoder.encode(ByteArray(0))).isNull()
    }
}
