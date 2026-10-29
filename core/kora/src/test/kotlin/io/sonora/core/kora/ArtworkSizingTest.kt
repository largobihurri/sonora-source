package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.media.MediaArtwork
import org.junit.Test

/** A cover whose JPEG size is `pixels * quality / divisor`, so the step-down is exercised without a bitmap. */
internal class FakeCover(
    override val widthPx: Int,
    override val heightPx: Int,
    private val bytesPerPixelPercent: Int = 100,
) : CoverImage {
    val encodings = mutableListOf<Triple<Int, Int, Int>>()

    override fun jpeg(widthPx: Int, heightPx: Int, quality: Int): ByteArray {
        encodings += Triple(widthPx, heightPx, quality)
        val size = (widthPx.toLong() * heightPx * quality * bytesPerPixelPercent / 10_000).toInt().coerceAtLeast(1)
        return ByteArray(size)
    }
}

class ArtworkSizingTest {
    private val cap = MediaArtwork.MAX_ARTWORK_BYTES

    @Test
    fun `a size keeps the aspect ratio with the longer edge at the target`() {
        assertThat(ArtworkSizing.scaledSize(1000, 500, 512)).isEqualTo(512 to 256)
        assertThat(ArtworkSizing.scaledSize(500, 1000, 512)).isEqualTo(256 to 512)
        assertThat(ArtworkSizing.scaledSize(1000, 1000, 100)).isEqualTo(100 to 100)
    }

    @Test
    fun `an extreme ratio never rounds a side to zero`() {
        assertThat(ArtworkSizing.scaledSize(4000, 10, 16)).isEqualTo(16 to 1)
    }

    @Test
    fun `a smaller cover is not upscaled`() {
        assertThat(ArtworkSizing.startEdge(300, 200, 512)).isEqualTo(300)
        assertThat(ArtworkSizing.startEdge(2000, 1500, 512)).isEqualTo(512)
        assertThat(ArtworkSizing.startEdge(300, 300, 300)).isEqualTo(300)
    }

    @Test
    fun `a small cover is encoded at its own size and the real size is reported`() {
        val cover = FakeCover(300, 200, bytesPerPixelPercent = 10)

        val encoded = ArtworkSizing.encodeUnderCap(cover, requestedEdgePx = 512, capBytes = cap)

        assertThat(encoded.widthPx).isEqualTo(300)
        assertThat(encoded.heightPx).isEqualTo(200)
        assertThat(cover.encodings).containsExactly(Triple(300, 200, 85))
    }

    @Test
    fun `a cover that fits at once is encoded once at quality 85`() {
        val cover = FakeCover(1000, 1000, bytesPerPixelPercent = 10)

        val encoded = ArtworkSizing.encodeUnderCap(cover, 512, cap)

        assertThat(cover.encodings).containsExactly(Triple(512, 512, 85))
        assertThat(encoded.bytes.size).isAtMost(cap)
    }

    @Test
    fun `too big lowers the quality first and keeps the size`() {
        // 512x512 at 45% per pixel: q85 -> 100 KB (over the cap), q75 -> 88 KB (fits).
        val big = FakeCover(1000, 1000, bytesPerPixelPercent = 45)

        val encoded = ArtworkSizing.encodeUnderCap(big, 512, cap)

        assertThat(big.encodings.map { it.third }).containsExactly(85, 75).inOrder()
        assertThat(big.encodings.map { it.first }.distinct()).containsExactly(512)
        assertThat(encoded.widthPx).isEqualTo(512)
        assertThat(encoded.bytes.size).isAtMost(cap)
    }

    @Test
    fun `when no quality fits the size shrinks by a quarter and the quality starts again`() {
        val cover = FakeCover(1000, 1000, bytesPerPixelPercent = 100)

        val encoded = ArtworkSizing.encodeUnderCap(cover, 512, cap)

        val edges = cover.encodings.map { it.first }.distinct()
        assertThat(edges).containsExactly(512, 384).inOrder() // 384 px at quality 55 is 81 KB, under the 90 KiB cap
        assertThat(cover.encodings.take(5).map { it.third }).containsExactly(85, 75, 65, 55, 45).inOrder()
        assertThat(cover.encodings[5]).isEqualTo(Triple(384, 384, 85))
        assertThat(encoded.bytes.size).isAtMost(cap)
        assertThat(cover.encodings.last()).isEqualTo(Triple(384, 384, 55))
        assertThat(encoded.widthPx).isEqualTo(384)
        assertThat(encoded.heightPx).isEqualTo(384)
    }

    @Test
    fun `the result always fits the cap even for a hopeless cover`() {
        val cover = FakeCover(1024, 1024, bytesPerPixelPercent = 10_000)

        val encoded = ArtworkSizing.encodeUnderCap(cover, 1024, cap)

        assertThat(encoded.bytes.size).isAtMost(cap)
        assertThat(encoded.widthPx).isAtLeast(1)
    }

    @Test
    fun `decoding samples down by powers of two but never below the requested edge`() {
        assertThat(AndroidCoverSource.sampleSize(4000, 3000, 512)).isEqualTo(4)
        assertThat(AndroidCoverSource.sampleSize(1024, 1024, 512)).isEqualTo(2)
        assertThat(AndroidCoverSource.sampleSize(1000, 1000, 512)).isEqualTo(1)
        assertThat(AndroidCoverSource.sampleSize(300, 200, 512)).isEqualTo(1)
    }
}
