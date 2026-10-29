package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DuplicateGroupingTest {

    private fun names(groups: List<DuplicateGroup>) = groups.map { group -> group.copies.map { it.uri.substringAfterLast('/') } }

    @Test
    fun `files with the same bytes are identical copies, whatever their tags`() {
        val groups = DuplicateGrouping.group(
            listOf(copy("a", title = null, hash = "h1"), copy("b", title = "Other", hash = "h1"), copy("c", hash = "h2")),
        )
        assertThat(names(groups)).containsExactly(listOf("a", "b"))
        assertThat(groups.single().kind).isEqualTo(DuplicateKind.IDENTICAL)
    }

    @Test
    fun `the same song in different files is grouped best copy first`() {
        val groups = DuplicateGrouping.group(
            listOf(mp3("mp3", title = "song (Remastered)"), copy("flac", durationMs = 201_500), copy("other", title = "Another")),
        )
        assertThat(names(groups)).containsExactly(listOf("flac", "mp3"))
        assertThat(groups.single().kind).isEqualTo(DuplicateKind.SAME_SONG)
        assertThat(groups.single().suggested.uri).endsWith("/flac")
    }

    @Test
    fun `different lengths, versions, artists and untagged files are not merged`() {
        val groups = DuplicateGrouping.group(
            listOf(
                copy("song"),
                copy("long", durationMs = 203_000),
                copy("remix", title = "Song (Remix)"),
                copy("cover", artist = "Other Band"),
                copy("untagged1", title = null, artist = null),
                copy("untagged2", title = null, artist = null),
                copy("unknown length", durationMs = 0),
            ),
        )
        assertThat(groups).isEmpty()
    }

    @Test
    fun `a length chain does not pull in a copy more than two seconds from the shortest`() {
        val groups = DuplicateGrouping.group(listOf(copy("a", durationMs = 200_000), copy("b", durationMs = 201_900), copy("c", durationMs = 203_800)))
        assertThat(names(groups)).containsExactly(listOf("a", "b"))
    }

    @Test
    fun `identical bytes and a re-encode of the same song form one group`() {
        val groups = DuplicateGrouping.group(listOf(copy("a", hash = "h"), copy("b", hash = "h"), mp3("c")))
        assertThat(names(groups).single()).containsExactly("a", "b", "c")
        assertThat(groups.single().kind).isEqualTo(DuplicateKind.SAME_SONG)
    }

    @Test
    fun `groups are listed by title and named by their files`() {
        val groups = DuplicateGrouping.group(
            listOf(copy("z1", title = "Zebra"), copy("z2", title = "zebra"), copy("a1", title = "Apple"), copy("a2", title = "Apple")),
        )
        assertThat(groups.map { it.suggested.title }).containsExactly("Apple", "Zebra").inOrder()
        assertThat(groups.first().key).isEqualTo(DuplicateGroup.keyOf(listOf("content://music/a2", "content://music/a1")))
    }
}
