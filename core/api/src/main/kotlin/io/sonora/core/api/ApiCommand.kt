package io.sonora.core.api

import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.playback.SleepTimerOption

/** Where `queue_add` puts the tracks. */
enum class QueuePlacement(val wireName: String) {
    /** At the end of the queue. */
    END("end"),

    /** Right after the current track. */
    NEXT("next"),

    /** Right after the current track, and play them now. */
    NOW("now");

    companion object {
        fun fromWireName(name: String): QueuePlacement? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * How a `play_*` command starts: [shuffle] shuffles the new queue, [startIndex] is the position (in the order the
 * tracks were resolved, before any shuffle) of the track to start from.
 */
data class PlayOptions(val shuffle: Boolean = false, val startIndex: Int = 0)

/**
 * A validated command. A parser builds it from a method name and extras ([CommandParser]); [ApiDispatcher] runs it.
 * Ids are library ids (never URIs or file paths); indices refer to the rows of the `queue` query.
 */
sealed interface ApiCommand {
    val method: ApiMethod

    data object Play : ApiCommand { override val method = ApiMethod.PLAY }
    data object Pause : ApiCommand { override val method = ApiMethod.PAUSE }
    data object Toggle : ApiCommand { override val method = ApiMethod.TOGGLE }
    data object Next : ApiCommand { override val method = ApiMethod.NEXT }
    data object Previous : ApiCommand { override val method = ApiMethod.PREVIOUS }

    data class Seek(val positionMs: Long) : ApiCommand { override val method = ApiMethod.SEEK }
    data class Shuffle(val enabled: Boolean) : ApiCommand { override val method = ApiMethod.SHUFFLE }
    data class Repeat(val mode: RepeatMode) : ApiCommand { override val method = ApiMethod.REPEAT }

    data class QueueAdd(val trackIds: List<Long>, val placement: QueuePlacement) : ApiCommand {
        override val method = ApiMethod.QUEUE_ADD
    }

    data class QueueRemove(val index: Int) : ApiCommand { override val method = ApiMethod.QUEUE_REMOVE }
    data class QueueMove(val from: Int, val to: Int) : ApiCommand { override val method = ApiMethod.QUEUE_MOVE }
    data object QueueClearUpcoming : ApiCommand { override val method = ApiMethod.QUEUE_CLEAR_UPCOMING }
    data class QueueSkipTo(val index: Int) : ApiCommand { override val method = ApiMethod.QUEUE_SKIP_TO }

    data class PlayAlbum(val albumId: Long, val options: PlayOptions) : ApiCommand {
        override val method = ApiMethod.PLAY_ALBUM
    }

    data class PlayArtist(val artistId: Long, val options: PlayOptions) : ApiCommand {
        override val method = ApiMethod.PLAY_ARTIST
    }

    data class PlayPlaylist(val playlistId: Long, val options: PlayOptions) : ApiCommand {
        override val method = ApiMethod.PLAY_PLAYLIST
    }

    data class PlaySearch(val query: String, val options: PlayOptions) : ApiCommand {
        override val method = ApiMethod.PLAY_SEARCH
    }

    data class PlayTracks(val trackIds: List<Long>, val options: PlayOptions) : ApiCommand {
        override val method = ApiMethod.PLAY_TRACKS
    }

    data class SleepStart(val option: SleepTimerOption) : ApiCommand { override val method = ApiMethod.SLEEP_START }
    data class SleepExtend(val minutes: Int) : ApiCommand { override val method = ApiMethod.SLEEP_EXTEND }
    data object SleepCancel : ApiCommand { override val method = ApiMethod.SLEEP_CANCEL }

    data class Crossfade(val seconds: Int) : ApiCommand { override val method = ApiMethod.CROSSFADE }

    /** [speed] is a factor: 1.0 is normal. */
    data class Speed(val speed: Double) : ApiCommand { override val method = ApiMethod.SPEED }
    data class DjMode(val enabled: Boolean) : ApiCommand { override val method = ApiMethod.DJ_MODE }
}
