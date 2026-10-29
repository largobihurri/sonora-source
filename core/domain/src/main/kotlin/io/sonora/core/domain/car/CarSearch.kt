package io.sonora.core.domain.car

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Track
import java.text.Normalizer

/** Songs, albums and artists matching a search, best first. */
data class CarSearchResults(val songs: List<Track>, val albums: List<Album>, val artists: List<Artist>) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/** What a voice request names, from Android Auto's media focus extras. */
enum class CarVoiceFocus { ARTIST, ALBUM, SONG }

/** "Play [query] on Sonora", with the structured fields the assistant recognised in it. */
data class CarVoiceRequest(
    val query: String,
    val focus: CarVoiceFocus? = null,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
)

/** What a voice request plays. */
sealed interface CarVoicePlan {
    /** A queue to play from its start. */
    data class Play(val queue: CarQueue) : CarVoicePlan

    /** Nothing in particular was asked for: what was playing, or else [songs] (the whole library). */
    data class Anything(val songs: List<Track>) : CarVoicePlan

    data object NoMatch : CarVoicePlan
}

/**
 * Search for the car and voice: case- and accent-insensitive; a name equal to the query ranks first, then names
 * starting with it, then names with a word starting with it, then names containing it. Songs also match by their
 * artist or album, after every title match. Ties keep the lists' own order (by title).
 */
object CarSearch {
    const val SONG_LIMIT = 100
    const val GROUP_LIMIT = 30

    fun rank(query: String, tracks: List<Track>, albums: List<Album>, artists: List<Artist>): CarSearchResults {
        val q = normalize(query)
        if (q.isEmpty()) return CarSearchResults(emptyList(), emptyList(), emptyList())
        return CarSearchResults(
            songs = tracks.ranked(SONG_LIMIT) { track ->
                score(track.title, q) ?: if (normalize(track.artist).contains(q) || normalize(track.album).contains(q)) OTHER_FIELD else null
            },
            albums = albums.ranked(GROUP_LIMIT) { score(it.title, q) },
            artists = artists.ranked(GROUP_LIMIT) { score(it.name, q) },
        )
    }

    /** How well [name] matches the normalised query [q]: lower is better, `null` is no match. */
    fun score(name: String, q: String): Int? {
        val n = normalize(name)
        return when {
            n == q -> EXACT
            n.startsWith(q) -> PREFIX
            n.split(' ').any { it.startsWith(q) } || n.contains(" $q") -> WORD_PREFIX
            n.contains(q) -> CONTAINS
            else -> null
        }
    }

    const val EXACT = 0
    private const val PREFIX = 1
    private const val WORD_PREFIX = 2
    private const val CONTAINS = 3
    private const val OTHER_FIELD = 4

    /** Lower case, accents folded, spaces collapsed. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").trim().replace(SPACES, " ")

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    private inline fun <T> List<T>.ranked(limit: Int, score: (T) -> Int?): List<T> =
        mapNotNull { item -> score(item)?.let { item to it } }.sortedBy { it.second }.take(limit).map { it.first }
}
