package io.sonora.core.api

import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.playback.Crossfade
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.SleepTimerOption

/** The outcome of parsing: a command or the reason there is none. */
sealed interface ParseResult {
    data class Parsed(val command: ApiCommand) : ParseResult
    data class Rejected(val error: ApiError) : ParseResult
}

/**
 * Builds an [ApiCommand] from a method name and its extras, without `android.os.Bundle`: the provider adapter turns the
 * `Bundle` into the map. A value may be native (`Long`, `Boolean`, a list) or text, as a shell sends it. Extras no
 * method uses are ignored, but any string among them that looks like a URI or a file path is rejected first.
 *
 * | Method | Arguments |
 * |---|---|
 * | `play`, `pause`, `toggle`, `next`, `previous`, `queue_clear_upcoming`, `sleep_cancel` | none |
 * | `seek` | `position_ms` (integer, 0 or more) |
 * | `shuffle`, `dj_mode` | `enabled` (boolean) |
 * | `repeat` | `mode` (`off`, `all`, `one`) |
 * | `queue_add` | `ids`; `placement` (`end` default, `next`, `now`) |
 * | `queue_remove`, `queue_skip_to` | `index` |
 * | `queue_move` | `from`, `to` |
 * | `play_album`, `play_artist`, `play_playlist` | `id`; optional `shuffle`, `start_index` |
 * | `play_search` | `q` (text); optional `shuffle`, `start_index` |
 * | `play_tracks` | `ids`; optional `shuffle`, `start_index` |
 * | `sleep_start` | `minutes` (1 to [ApiLimits.MAX_SLEEP_MINUTES]) or `mode` (`end_of_song`, `end_of_album`) |
 * | `sleep_extend` | `minutes` |
 * | `crossfade` | `seconds` (0 to [Crossfade.MAX_SECONDS]) |
 * | `speed` | `speed` (factor, 0.5 to 2.0) |
 */
object CommandParser {

    fun parse(method: String, extras: Map<String, Any?> = emptyMap()): ParseResult = try {
        val known = ApiMethod.fromWireName(method) ?: throw ApiFailure(ApiError.UnknownMethod(method))
        rejectLocations(extras)
        ParseResult.Parsed(build(known, ArgumentReader(extras)))
    } catch (failure: ApiFailure) {
        ParseResult.Rejected(failure.error)
    }

    private fun build(method: ApiMethod, args: ArgumentReader): ApiCommand = when (method) {
        ApiMethod.PLAY -> ApiCommand.Play
        ApiMethod.PAUSE -> ApiCommand.Pause
        ApiMethod.TOGGLE -> ApiCommand.Toggle
        ApiMethod.NEXT -> ApiCommand.Next
        ApiMethod.PREVIOUS -> ApiCommand.Previous
        ApiMethod.SEEK -> ApiCommand.Seek(positionMs = nonNegativeLong(args, "position_ms"))
        ApiMethod.SHUFFLE -> ApiCommand.Shuffle(args.boolean("enabled"))
        ApiMethod.REPEAT -> ApiCommand.Repeat(repeatMode(args.string("mode")))
        ApiMethod.QUEUE_ADD -> ApiCommand.QueueAdd(args.ids("ids"), placement(args))
        ApiMethod.QUEUE_REMOVE -> ApiCommand.QueueRemove(index(args, "index"))
        ApiMethod.QUEUE_MOVE -> ApiCommand.QueueMove(index(args, "from"), index(args, "to"))
        ApiMethod.QUEUE_CLEAR_UPCOMING -> ApiCommand.QueueClearUpcoming
        ApiMethod.QUEUE_SKIP_TO -> ApiCommand.QueueSkipTo(index(args, "index"))
        ApiMethod.PLAY_ALBUM -> ApiCommand.PlayAlbum(args.id("id"), playOptions(args))
        ApiMethod.PLAY_ARTIST -> ApiCommand.PlayArtist(args.id("id"), playOptions(args))
        ApiMethod.PLAY_PLAYLIST -> ApiCommand.PlayPlaylist(args.id("id"), playOptions(args))
        ApiMethod.PLAY_SEARCH -> ApiCommand.PlaySearch(searchText(args, "q"), playOptions(args))
        ApiMethod.PLAY_TRACKS -> ApiCommand.PlayTracks(args.ids("ids"), playOptions(args))
        ApiMethod.SLEEP_START -> ApiCommand.SleepStart(sleepOption(args))
        ApiMethod.SLEEP_EXTEND -> ApiCommand.SleepExtend(sleepMinutes(args))
        ApiMethod.SLEEP_CANCEL -> ApiCommand.SleepCancel
        ApiMethod.CROSSFADE ->
            ApiCommand.Crossfade(args.int("seconds", Crossfade.MIN_SECONDS..Crossfade.MAX_SECONDS))
        ApiMethod.SPEED -> ApiCommand.Speed(speed(args))
        ApiMethod.DJ_MODE -> ApiCommand.DjMode(args.boolean("enabled"))
    }

    private fun nonNegativeLong(args: ArgumentReader, name: String): Long {
        val value = args.long(name)
        if (value < 0) throw ApiFailure(ApiError.ArgumentOutOfRange(name, "0 or more"))
        return value
    }

    private fun index(args: ArgumentReader, name: String): Int = args.int(name, 0..Int.MAX_VALUE)

    private fun playOptions(args: ArgumentReader) =
        PlayOptions(shuffle = args.boolean("shuffle", default = false), startIndex = args.int("start_index", 0..Int.MAX_VALUE, default = 0))

    private fun repeatMode(text: String): RepeatMode =
        RepeatMode.entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
            ?: throw ApiFailure(ApiError.BadArgumentValue("mode", "one of off, all, one"))

    private fun placement(args: ArgumentReader): QueuePlacement {
        val text = args.stringOrNull("placement") ?: return QueuePlacement.END
        return QueuePlacement.entries.firstOrNull { it.wireName.equals(text.trim(), ignoreCase = true) }
            ?: throw ApiFailure(ApiError.BadArgumentValue("placement", "one of end, next, now"))
    }

    private fun searchText(args: ArgumentReader, name: String): String {
        val text = args.string(name).trim()
        if (text.isEmpty() || text.length > ApiLimits.MAX_QUERY_LENGTH) {
            throw ApiFailure(ApiError.ArgumentOutOfRange(name, "1 to ${ApiLimits.MAX_QUERY_LENGTH} characters"))
        }
        return text
    }

    private fun sleepMinutes(args: ArgumentReader): Int = args.int("minutes", 1..ApiLimits.MAX_SLEEP_MINUTES)

    private fun sleepOption(args: ArgumentReader): SleepTimerOption {
        val hasMode = args.has("mode")
        if (args.has("minutes") && hasMode) {
            throw ApiFailure(ApiError.BadArgumentValue("mode", "cannot be combined with minutes"))
        }
        if (!hasMode) return SleepTimerOption.Minutes(sleepMinutes(args))
        return when (args.string("mode").trim().lowercase()) {
            "end_of_song" -> SleepTimerOption.EndOfSong
            "end_of_album" -> SleepTimerOption.EndOfAlbum
            else -> throw ApiFailure(ApiError.BadArgumentValue("mode", "one of end_of_song, end_of_album"))
        }
    }

    private fun speed(args: ArgumentReader): Double {
        val value = args.number("speed")
        val min = PlaybackTempo.MIN_SPEED_PERCENT / 100.0
        val max = PlaybackTempo.MAX_SPEED
        if (value < min || value > max) throw ApiFailure(ApiError.ArgumentOutOfRange("speed", "$min..$max"))
        return value
    }
}
