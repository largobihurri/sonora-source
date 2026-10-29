package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SongMatchTest {

    private fun sameSong(a: String, b: String) = SongMatch.title(a) == SongMatch.title(b)

    @Test
    fun `case, accents, spacing and punctuation do not tell songs apart`() {
        assertThat(sameSong("Canción  de Amor", "cancion de amor")).isTrue()
        assertThat(sameSong("Don't Stop Me Now", "Dont Stop Me Now!")).isTrue()
        assertThat(sameSong("Rock & Roll", "Rock and Roll")).isTrue()
        assertThat(SongMatch.artist("Beyoncé")).isEqualTo(SongMatch.artist("BEYONCE"))
    }

    @Test
    fun `featured artists are left out of titles and artists`() {
        assertThat(sameSong("Song (feat. Guest)", "Song")).isTrue()
        assertThat(sameSong("Song [ft. Guest]", "Song")).isTrue()
        assertThat(sameSong("Song feat. Guest", "Song")).isTrue()
        assertThat(sameSong("Song Featuring Guest", "Song")).isTrue()
        assertThat(SongMatch.artist("Band feat. Guest")).isEqualTo(SongMatch.artist("Band"))
        assertThat(SongMatch.artist("Band ft Guest & Other")).isEqualTo(SongMatch.artist("Band"))
        assertThat(SongMatch.artist("Band (featuring Guest)")).isEqualTo(SongMatch.artist("Band"))
    }

    @Test
    fun `remaster, explicit and album version tags are the same recording`() {
        assertThat(sameSong("Song (Remastered 2011)", "Song")).isTrue()
        assertThat(sameSong("Song - 2009 Remaster", "Song")).isTrue()
        assertThat(sameSong("Song [Explicit]", "Song")).isTrue()
        assertThat(sameSong("Song (Album Version)", "Song")).isTrue()
        assertThat(sameSong("Song (Original Mix)", "Song")).isTrue()
        assertThat(sameSong("Song (Remasterizado)", "Song")).isTrue()
        assertThat(sameSong("Song (feat. Guest) [2011 Remaster]", "Song")).isTrue()
    }

    @Test
    fun `remixes, edits, live takes and other versions are different songs`() {
        assertThat(sameSong("Song (Remix)", "Song")).isFalse()
        assertThat(sameSong("Song (DJ X Remix)", "Song (DJ Y Remix)")).isFalse()
        assertThat(sameSong("Song (Radio Edit)", "Song")).isFalse()
        assertThat(sameSong("Song - Live", "Song")).isFalse()
        assertThat(sameSong("Song (Live at Wembley 1986)", "Song (Remastered)")).isFalse()
        assertThat(sameSong("Song (Acoustic Version)", "Song")).isFalse()
        assertThat(sameSong("Song (Extended Mix)", "Song (Original Mix)")).isFalse()
        assertThat(sameSong("Song (Single Version)", "Song")).isFalse()
        assertThat(sameSong("Song (Instrumental)", "Song")).isFalse()
        assertThat(sameSong("Song (Mono)", "Song")).isFalse()
    }

    @Test
    fun `a version tag after a featured artist still tells the songs apart`() {
        assertThat(sameSong("Song feat. Guest (Remix)", "Song")).isFalse()
        assertThat(sameSong("Song feat. Guest (Remix)", "Song (Remix)")).isTrue()
    }

    @Test
    fun `lengths match within two seconds`() {
        assertThat(SongMatch.sameLength(200_000, 202_000)).isTrue()
        assertThat(SongMatch.sameLength(202_000, 200_000)).isTrue()
        assertThat(SongMatch.sameLength(200_000, 202_001)).isFalse()
        assertThat(SongMatch.sameLength(0, 0)).isFalse()
    }

    @Test
    fun `only files tagged with a title and an artist have a song key`() {
        assertThat(SongMatch.key(copy("a", title = "Song (feat. X)", artist = "Band"))).isEqualTo(SongMatch.key(copy("b")))
        assertThat(SongMatch.key(copy("a", title = null))).isNull()
        assertThat(SongMatch.key(copy("a", artist = " "))).isNull()
        assertThat(SongMatch.key(copy("a", title = "(Remastered)"))).isNull()
        assertThat(SongMatch.key(copy("a", artist = "Other"))).isNotEqualTo(SongMatch.key(copy("b")))
    }
}
