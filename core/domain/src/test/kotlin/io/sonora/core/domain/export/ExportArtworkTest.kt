package io.sonora.core.domain.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ExportArtworkTest {

    @Test
    fun `device images are equal when their bytes are`() {
        val one = DeviceArtworkImages(byteArrayOf(1, 2), byteArrayOf(3))

        assertThat(one).isEqualTo(DeviceArtworkImages(byteArrayOf(1, 2), byteArrayOf(3)))
        assertThat(one.hashCode()).isEqualTo(DeviceArtworkImages(byteArrayOf(1, 2), byteArrayOf(3)).hashCode())
        assertThat(one).isNotEqualTo(DeviceArtworkImages(byteArrayOf(1, 2), byteArrayOf(4)))
    }

    @Test
    fun `no artwork gives no pictures`() = runBlocking {
        assertThat(ExportArtwork.NONE.libraryPicture("content://x")).isNull()
        assertThat(ExportArtwork.NONE.deviceImages(byteArrayOf(1))).isNull()
    }
}
