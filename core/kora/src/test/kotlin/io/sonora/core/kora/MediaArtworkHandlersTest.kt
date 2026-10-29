package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.runBlocking
import org.junit.Test

private class FakeCovers(var byUri: Map<String, CoverImage?> = emptyMap()) : CoverSource {
    val opened = mutableListOf<Pair<String, Int>>()

    override fun open(uri: String, maxEdgePx: Int): CoverImage? {
        opened += uri to maxEdgePx
        return byUri[uri]
    }
}

/** Goes through a real [KoraNode], so a handler missing from the registration fails here, not at app start. */
class MediaArtworkHandlersTest {
    private val songs = listOf(
        track(1, albumId = 40, artworkUri = "file:///cache/art/40.jpg"),
        track(2, albumId = 40, artworkUri = "file:///cache/art/40.jpg"),
        track(3, albumId = 41, artworkUri = null),
        track(4, albumId = 42, artworkUri = "file:///cache/art/42.jpg"),
    )
    private val library = FakeLibrary(songs)
    private val covers = FakeCovers(
        mapOf(
            "file:///cache/art/40.jpg" to FakeCover(1000, 800, bytesPerPixelPercent = 10),
            "file:///cache/art/42.jpg" to null,
        ),
    )
    private val node = KoraNode("test.sonora").apply {
        val handlers = MediaArtworkHandlers({ library }, covers)
        provide(MediaArtwork) { handlers.register(this) }
    }

    private suspend fun artwork(id: String, edge: Int = 512) = node.call(MediaArtwork.GetArtwork, ArtworkRequest(id, edge))

    @Test
    fun `the album key of any of its tracks answers the cover scaled to the requested edge`() = runBlocking<Unit> {
        val first = artwork(trackSummary(songs[0]).artworkId!!)
        val second = artwork(trackSummary(songs[1]).artworkId!!)

        assertThat(first.mimeType).isEqualTo(ArtworkMimeType.JPEG)
        assertThat(first.widthPx).isEqualTo(512)
        assertThat(first.heightPx).isEqualTo(410)
        assertThat(first.bytes.size).isAtMost(MediaArtwork.MAX_ARTWORK_BYTES)
        assertThat(second.widthPx).isEqualTo(first.widthPx)
        assertThat(covers.opened).containsExactly("file:///cache/art/40.jpg" to 512, "file:///cache/art/40.jpg" to 512)
    }

    @Test
    fun `a cover smaller than the request is not upscaled`() = runBlocking<Unit> {
        covers.byUri = mapOf("file:///cache/art/40.jpg" to FakeCover(300, 200, bytesPerPixelPercent = 10))

        val image = artwork("a:40", edge = 1024)

        assertThat(image.widthPx).isEqualTo(300)
        assertThat(image.heightPx).isEqualTo(200)
    }

    @Test
    fun `a heavy cover is brought under the cap`() = runBlocking<Unit> {
        covers.byUri = mapOf("file:///cache/art/40.jpg" to FakeCover(1000, 1000, bytesPerPixelPercent = 100))

        val image = artwork("a:40", edge = 1024)

        assertThat(image.bytes.size).isAtMost(MediaArtwork.MAX_ARTWORK_BYTES)
        assertThat(image.widthPx).isLessThan(1024)
    }

    @Test
    fun `an unknown album is an invalid payload`() = runBlocking<Unit> {
        assertThat(koraFailure { artwork("a:999") }.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `an album without artwork is an invalid payload`() = runBlocking<Unit> {
        assertThat(koraFailure { artwork("a:41") }.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `a cover that fails to decode is an invalid payload`() = runBlocking<Unit> {
        assertThat(koraFailure { artwork("a:42") }.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `keys that are not album keys are invalid payloads and never reach the cover source`() = runBlocking<Unit> {
        listOf("t:1", "40", "a:", "a:-1", "a:4x", "content://media/art/1", "/sdcard/cover.jpg", "a:99999999999999999999").forEach { id ->
            assertThat(koraFailure { artwork(id) }.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        }
        assertThat(covers.opened).isEmpty()
    }

    @Test
    fun `the error never echoes the key`() = runBlocking<Unit> {
        val failure = koraFailure { artwork("a:999") }

        assertThat(failure.error.message).doesNotContain("999")
        assertThat(failure.error.message).doesNotContain("file:")
    }
}
