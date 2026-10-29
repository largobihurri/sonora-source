package io.sonora.core.domain.backup

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Where a backed-up song is in the current library, if anywhere. */
sealed interface SongMatch {
    /** The library holds a song with the very same document key. */
    data class ByKey(val song: SongIdentity) : SongMatch

    /** No song has the key, but exactly one has the same tags. */
    data class ByTags(val song: SongIdentity) : SongMatch

    /** No song has the key and [candidates] songs share its tags, so none is picked. */
    data class Ambiguous(val candidates: Int) : SongMatch

    data object Missing : SongMatch

    /** The library song this match restores onto; `null` when there is none. */
    val target: SongIdentity?
        get() = when (this) {
            is ByKey -> song
            is ByTags -> song
            is Ambiguous, Missing -> null
        }
}

/** Finds backed-up songs in the current library: by document key first, then by tags. */
object SongMatcher {
    /** Durations further apart than this are different songs (or different edits of one). */
    const val DURATION_TOLERANCE_MS = 2_000L

    /**
     * The match of each of [songs] (by key) in [library]. A tag match needs the same title and artist, the same album
     * when both name one, and durations within [DURATION_TOLERANCE_MS] when both are known; case, accents' composition
     * and runs of spaces do not count. A song without a title never matches by tags.
     */
    fun match(songs: Collection<SongIdentity>, library: Collection<SongIdentity>): Map<String, SongMatch> {
        val byKey = library.associateBy { it.key }
        val byTitleArtist = library.filter { it.title.isNotBlank() }.groupBy { TitleArtist.of(it) }
        return songs.associate { song ->
            song.key to (byKey[song.key]?.let(SongMatch::ByKey) ?: byTags(song, byTitleArtist))
        }
    }

    private fun byTags(song: SongIdentity, byTitleArtist: Map<TitleArtist, List<SongIdentity>>): SongMatch {
        if (song.title.isBlank()) return SongMatch.Missing
        val album = normalize(song.album)
        val candidates = byTitleArtist[TitleArtist.of(song)].orEmpty().filter { candidate ->
            val candidateAlbum = normalize(candidate.album)
            (album.isEmpty() || candidateAlbum.isEmpty() || album == candidateAlbum) &&
                (song.durationMs <= 0 || candidate.durationMs <= 0 ||
                    abs(song.durationMs - candidate.durationMs) <= DURATION_TOLERANCE_MS)
        }
        return when (candidates.size) {
            0 -> SongMatch.Missing
            1 -> SongMatch.ByTags(candidates.single())
            else -> SongMatch.Ambiguous(candidates.size)
        }
    }

    private data class TitleArtist(val title: String, val artist: String) {
        companion object {
            fun of(song: SongIdentity) = TitleArtist(normalize(song.title), normalize(song.artist))
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** Composed, lower-cased and with runs of spaces made one, so spellings of one tag compare equal. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim().replace(WHITESPACE, " ")
}
