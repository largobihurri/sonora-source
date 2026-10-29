package io.sonora.core.domain.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SongMatcherTest {

    private val halo = SongIdentity("content://old/halo", "Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000)
    private val libraryHalo = halo.copy(key = "content://new/halo")

    @Test
    fun `a song whose key the library has matches by key, even when its tags changed`() {
        val retagged = halo.copy(title = "Halo (Remastered)")

        val matches = SongMatcher.match(listOf(halo), listOf(retagged, libraryHalo))

        assertThat(matches).containsExactly(halo.key, SongMatch.ByKey(retagged))
    }

    @Test
    fun `a song whose key is gone matches the one library song with its tags`() {
        val other = SongIdentity("content://new/other", "Halo", "Someone Else", "Covers", 250_000)

        val matches = SongMatcher.match(listOf(halo), listOf(other, libraryHalo))

        assertThat(matches[halo.key]).isEqualTo(SongMatch.ByTags(libraryHalo))
        assertThat(matches[halo.key]?.target).isEqualTo(libraryHalo)
    }

    @Test
    fun `tag matching ignores case, spacing and how accents are composed`() {
        val decomposed = libraryHalo.copy(title = " halo ", artist = "BEYONCÉ", album = "I  Am...  Sasha Fierce")

        assertThat(SongMatcher.match(listOf(halo), listOf(decomposed))[halo.key]).isEqualTo(SongMatch.ByTags(decomposed))
    }

    @Test
    fun `a different album or a duration off by more than the tolerance is another song`() {
        val live = libraryHalo.copy(album = "Live at Wembley")
        val edit = libraryHalo.copy(durationMs = halo.durationMs + SongMatcher.DURATION_TOLERANCE_MS + 1)

        val matches = SongMatcher.match(listOf(halo), listOf(live, edit))

        assertThat(matches[halo.key]).isEqualTo(SongMatch.Missing)
    }

    @Test
    fun `an unknown album or duration on either side does not stop a tag match`() {
        val snapshot = halo.copy(album = "", durationMs = 0)
        val close = libraryHalo.copy(durationMs = halo.durationMs + SongMatcher.DURATION_TOLERANCE_MS)

        assertThat(SongMatcher.match(listOf(snapshot), listOf(libraryHalo))[halo.key]).isEqualTo(SongMatch.ByTags(libraryHalo))
        assertThat(SongMatcher.match(listOf(halo), listOf(close))[halo.key]).isEqualTo(SongMatch.ByTags(close))
    }

    @Test
    fun `several library songs with the same tags are ambiguous and none is picked`() {
        val copy = libraryHalo.copy(key = "content://new/halo-copy")

        val match = SongMatcher.match(listOf(halo), listOf(libraryHalo, copy))[halo.key]

        assertThat(match).isEqualTo(SongMatch.Ambiguous(2))
        assertThat(match?.target).isNull()
    }

    @Test
    fun `a song without a title never matches by tags`() {
        val untitled = halo.copy(title = "", artist = "")

        assertThat(SongMatcher.match(listOf(untitled), listOf(libraryHalo.copy(title = "", artist = "")))[halo.key])
            .isEqualTo(SongMatch.Missing)
    }
}
