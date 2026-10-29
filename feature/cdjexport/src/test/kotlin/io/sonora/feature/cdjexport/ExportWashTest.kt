package io.sonora.feature.cdjexport

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.ColorMath
import io.sonora.core.designsystem.theme.SonoraColors
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.model.Track
import org.junit.Test

class ExportWashTest {

    @Test
    fun `the first listed track tints the tab`() {
        val tracks = listOf(picked("content://a"), picked("content://b"))

        assertThat(ExportWash.sourceUri(tracks)).isEqualTo("content://a")
    }

    @Test
    fun `an empty list has nothing to tint it`() {
        assertThat(ExportWash.sourceUri(emptyList())).isNull()
    }

    @Test
    fun `the library gives the artwork of each listed track it knows a cover for`() {
        val library = listOf(
            track("content://a", artwork = "content://art/a"),
            track("content://b", artwork = "content://art/b"),
            track("content://other", artwork = "content://art/other"),
        )

        val found = ExportWash.artworkByUri(listOf("content://a", "content://b"), library)

        assertThat(found).containsExactly("content://a", "content://art/a", "content://b", "content://art/b")
    }

    @Test
    fun `a track the library does not index or has no cover for is left out`() {
        val library = listOf(
            track("content://bare", artwork = null),
            track("content://blank", artwork = "  "),
            track("content://covered", artwork = "content://art/covered"),
        )

        val found = ExportWash.artworkByUri(
            listOf("content://bare", "content://blank", "content://covered", "content://unknown"),
            library,
        )

        assertThat(found).containsExactly("content://covered", "content://art/covered")
    }

    @Test
    fun `nothing listed finds nothing`() {
        assertThat(ExportWash.artworkByUri(emptyList(), listOf(track("content://a", artwork = "x")))).isEmpty()
    }

    @Test
    fun `the cover's palette tints the tab once it is read`() {
        val cover = ArtworkPalette.from(RED)

        assertThat(ExportWash.palette(cover, hasCover = true, accent = BLUE)).isEqualTo(cover)
    }

    @Test
    fun `while the cover is read the app accent tints the tab, not grey`() {
        assertThat(ExportWash.palette(null, hasCover = true, accent = BLUE)).isEqualTo(ArtworkPalette.from(BLUE))
    }

    @Test
    fun `a track without a cover is tinted by the app accent whatever palette was left over`() {
        val leftOver = ArtworkPalette.from(RED)

        assertThat(ExportWash.palette(leftOver, hasCover = false, accent = BLUE)).isEqualTo(ArtworkPalette.from(BLUE))
    }

    @Test
    fun `without a cover or an accent the tab gets the neutral palette`() {
        assertThat(ExportWash.palette(null, hasCover = false, accent = null)).isEqualTo(ArtworkPalette.from(null))
    }

    @Test
    fun `the wash is a few patches placed on the tab`() {
        for (dark in listOf(true, false)) {
            val blobs = ExportWash.blobs(ArtworkPalette.from(RED), dark)

            assertThat(blobs).hasSize(3)
            for (blob in blobs) {
                assertThat(blob.centerX).isAtLeast(0f)
                assertThat(blob.centerX).isAtMost(1f)
                assertThat(blob.centerY).isAtLeast(0f)
                assertThat(blob.centerY).isAtMost(1f)
                assertThat(blob.radius).isGreaterThan(0.3f)
                assertThat(blob.alpha).isGreaterThan(0f)
                assertThat(blob.alpha).isAtMost(1f)
            }
        }
    }

    @Test
    fun `a light theme gets a fainter wash than a dark one`() {
        val palette = ArtworkPalette.from(RED)
        val dark = ExportWash.blobs(palette, dark = true)
        val light = ExportWash.blobs(palette, dark = false)

        assertThat(dark).isNotEmpty()
        assertThat(light).hasSize(dark.size)
        for (i in dark.indices) assertThat(light[i].alpha).isLessThan(dark[i].alpha)
    }

    @Test
    fun `the page's own text stays readable where every patch overlaps`() {
        // The strongest the wash gets: all patches over each other, on the theme's own background.
        for (dominant in listOf(null, RED, BLUE, YELLOW, WHITE, BLACK)) {
            val palette = ArtworkPalette.from(dominant)
            for (dark in listOf(true, false)) {
                val base = (if (dark) SonoraColors.DarkBackground else SonoraColors.LightBackground).toArgb()
                val text = if (dark) WHITE else BLACK
                val blobs = ExportWash.blobs(palette, dark)
                val worst = blobs.fold(base) { under, blob -> over(under, blob.argb, blob.alpha) }

                assertThat(blobs).isNotEmpty()
                assertThat(ColorMath.contrastRatio(text, worst)).isAtLeast(AA)
            }
        }
    }

    /** [color] at [alpha] over the opaque [under], per sRGB channel as the screen blends it. */
    private fun over(under: Int, color: Int, alpha: Float): Int {
        fun channel(shift: Int): Int {
            val a = (under shr shift) and 0xFF
            val b = (color shr shift) and 0xFF
            return (a + (b - a) * alpha).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun picked(uri: String) =
        PickedTrack(SourceFile(uri, "song.aiff", 1_000L, SourceAudio(AudioContainer.AIFF, 16, 44_100, 2)))

    private fun track(uri: String, artwork: String?) = Track(
        id = uri.hashCode().toLong(),
        uri = uri,
        title = "Title",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        durationMs = 1_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        albumId = 1L,
        artworkUri = artwork,
        mimeType = "audio/aiff",
        sizeBytes = 1_000L,
    )

    private companion object {
        const val AA = 4.5
        const val RED = 0xFFD32F2F.toInt()
        const val BLUE = 0xFF1E88E5.toInt()
        const val YELLOW = 0xFFFFEB3B.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
