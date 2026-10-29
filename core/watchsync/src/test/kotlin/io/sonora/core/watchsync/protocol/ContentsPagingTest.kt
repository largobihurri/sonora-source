package io.sonora.core.watchsync.protocol

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentsPagingTest {

    private fun track(n: Int, title: String = "Song $n") = ManifestTrack(
        id = "%016x".format(n),
        title = title,
        artist = "Artist",
        durationMs = 180_000,
        sizeBytes = 4_000_000,
        sha256 = "a".repeat(64),
    )

    @Test
    fun `a library that fits is one page with nothing more to come`() {
        val tracks = (1..5).map(::track)

        val pages = ContentsPaging.pages(tracks, maxPageBytes = 1_000_000)

        assertThat(pages).containsExactly(SyncMessage.Contents(tracks, more = false))
    }

    @Test
    fun `an empty library is still one page`() {
        assertThat(ContentsPaging.pages(emptyList(), maxPageBytes = 1_000)).containsExactly(SyncMessage.Contents(emptyList()))
    }

    @Test
    fun `a big library is split into pages that each fit the budget, in order, with more on all but the last`() {
        val tracks = (1..200).map(::track)
        val budget = 4_000

        val pages = ContentsPaging.pages(tracks, maxPageBytes = budget)

        assertThat(pages.size).isGreaterThan(5)
        assertThat(pages.flatMap { it.tracks }).containsExactlyElementsIn(tracks).inOrder()
        assertThat(pages.dropLast(1).map { it.more }.toSet()).containsExactly(true)
        assertThat(pages.last().more).isFalse()
        pages.forEach { assertThat(SyncMessageCodec.encode(it).size).isAtMost(budget) }
    }

    @Test
    fun `a song bigger than the budget still gets a page of its own`() {
        val tracks = listOf(track(1), track(2, title = "x".repeat(900)), track(3))

        val pages = ContentsPaging.pages(tracks, maxPageBytes = 500)

        assertThat(pages.flatMap { it.tracks }).containsExactlyElementsIn(tracks).inOrder()
        assertThat(pages.all { it.tracks.isNotEmpty() }).isTrue()
    }

    @Test
    fun `a budget that cannot hold even an empty page is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ContentsPaging.pages(listOf(track(1)), maxPageBytes = 0) }
    }
}
