package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError.ArgumentOutOfRange
import io.sonora.core.api.ApiError.BadArgumentType
import io.sonora.core.api.ApiError.BadArgumentValue
import io.sonora.core.api.ApiError.LocationRejected
import io.sonora.core.api.ApiError.MissingArgument
import io.sonora.core.api.ApiError.UnknownMethod
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.playback.SleepTimerOption
import org.junit.Test

class CommandParserTest {

    private fun parsed(method: String, vararg extras: Pair<String, Any?>): ApiCommand {
        val result = CommandParser.parse(method, mapOf(*extras))
        assertThat(result).isInstanceOf(ParseResult.Parsed::class.java)
        return (result as ParseResult.Parsed).command
    }

    private fun rejected(method: String, vararg extras: Pair<String, Any?>): ApiError {
        val result = CommandParser.parse(method, mapOf(*extras))
        assertThat(result).isInstanceOf(ParseResult.Rejected::class.java)
        return (result as ParseResult.Rejected).error
    }

    @Test
    fun `transport methods need no arguments`() {
        assertThat(parsed("play")).isEqualTo(ApiCommand.Play)
        assertThat(parsed("pause")).isEqualTo(ApiCommand.Pause)
        assertThat(parsed("toggle")).isEqualTo(ApiCommand.Toggle)
        assertThat(parsed("next")).isEqualTo(ApiCommand.Next)
        assertThat(parsed("previous")).isEqualTo(ApiCommand.Previous)
        assertThat(parsed("queue_clear_upcoming")).isEqualTo(ApiCommand.QueueClearUpcoming)
        assertThat(parsed("sleep_cancel")).isEqualTo(ApiCommand.SleepCancel)
    }

    @Test
    fun `every method has a wire name that parses back to itself`() {
        assertThat(ApiMethod.entries.map { it.wireName }).containsNoDuplicates()
        ApiMethod.entries.forEach { assertThat(ApiMethod.fromWireName(it.wireName)).isEqualTo(it) }
    }

    @Test
    fun `an unknown or differently cased method is rejected`() {
        assertThat(rejected("explode")).isEqualTo(UnknownMethod("explode"))
        assertThat(rejected("PLAY")).isEqualTo(UnknownMethod("PLAY"))
        assertThat(rejected("")).isEqualTo(UnknownMethod(""))
    }

    @Test
    fun `seek reads a position in milliseconds`() {
        assertThat(parsed("seek", "position_ms" to 90_000L)).isEqualTo(ApiCommand.Seek(90_000))
        assertThat(parsed("seek", "position_ms" to 0)).isEqualTo(ApiCommand.Seek(0))
    }

    @Test
    fun `seek without a position or a negative one is rejected`() {
        assertThat(rejected("seek")).isEqualTo(MissingArgument("position_ms"))
        assertThat(rejected("seek", "position_ms" to -1L)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("seek", "position_ms" to null)).isEqualTo(MissingArgument("position_ms"))
    }

    @Test
    fun `integers come as any whole number or as a decimal string, as adb sends them`() {
        assertThat(parsed("seek", "position_ms" to 5)).isEqualTo(ApiCommand.Seek(5))
        assertThat(parsed("seek", "position_ms" to 5.toShort())).isEqualTo(ApiCommand.Seek(5))
        assertThat(parsed("seek", "position_ms" to 5.0)).isEqualTo(ApiCommand.Seek(5))
        assertThat(parsed("seek", "position_ms" to " 5 ")).isEqualTo(ApiCommand.Seek(5))
    }

    @Test
    fun `a fraction, text or a boolean is not an integer`() {
        listOf(5.5, "five", true, "", listOf(1L)).forEach {
            assertThat(rejected("seek", "position_ms" to it)).isEqualTo(BadArgumentType("position_ms", "integer"))
        }
    }

    @Test
    fun `an integer too big for an index is out of range, not wrapped`() {
        assertThat(rejected("queue_skip_to", "index" to 3_000_000_000L)).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `shuffle and dj mode read a boolean, native or as true or false text`() {
        assertThat(parsed("shuffle", "enabled" to true)).isEqualTo(ApiCommand.Shuffle(true))
        assertThat(parsed("shuffle", "enabled" to "false")).isEqualTo(ApiCommand.Shuffle(false))
        assertThat(parsed("dj_mode", "enabled" to "TRUE")).isEqualTo(ApiCommand.DjMode(true))
        assertThat(rejected("dj_mode")).isEqualTo(MissingArgument("enabled"))
        assertThat(rejected("shuffle", "enabled" to "yes")).isEqualTo(BadArgumentType("enabled", "boolean"))
        assertThat(rejected("shuffle", "enabled" to 1)).isEqualTo(BadArgumentType("enabled", "boolean"))
    }

    @Test
    fun `repeat takes off, all or one in any case`() {
        assertThat(parsed("repeat", "mode" to "off")).isEqualTo(ApiCommand.Repeat(RepeatMode.OFF))
        assertThat(parsed("repeat", "mode" to "ALL")).isEqualTo(ApiCommand.Repeat(RepeatMode.ALL))
        assertThat(parsed("repeat", "mode" to "One")).isEqualTo(ApiCommand.Repeat(RepeatMode.ONE))
        assertThat(rejected("repeat", "mode" to "forever")).isInstanceOf(BadArgumentValue::class.java)
        assertThat(rejected("repeat")).isEqualTo(MissingArgument("mode"))
        assertThat(rejected("repeat", "mode" to 3)).isEqualTo(BadArgumentType("mode", "string"))
    }

    @Test
    fun `queue add takes library ids and defaults to the end of the queue`() {
        assertThat(parsed("queue_add", "ids" to listOf(3L, 1L, 3L)))
            .isEqualTo(ApiCommand.QueueAdd(listOf(3, 1, 3), QueuePlacement.END))
        assertThat(parsed("queue_add", "ids" to longArrayOf(7), "placement" to "next"))
            .isEqualTo(ApiCommand.QueueAdd(listOf(7), QueuePlacement.NEXT))
        assertThat(parsed("queue_add", "ids" to intArrayOf(7, 8), "placement" to "NOW"))
            .isEqualTo(ApiCommand.QueueAdd(listOf(7, 8), QueuePlacement.NOW))
    }

    @Test
    fun `ids also come as comma separated text or a single number`() {
        assertThat(parsed("queue_add", "ids" to "4, 5,6")).isEqualTo(ApiCommand.QueueAdd(listOf(4, 5, 6), QueuePlacement.END))
        assertThat(parsed("queue_add", "ids" to 9L)).isEqualTo(ApiCommand.QueueAdd(listOf(9), QueuePlacement.END))
        assertThat(parsed("queue_add", "ids" to arrayOf<Any>(1L, "2"))).isEqualTo(ApiCommand.QueueAdd(listOf(1, 2), QueuePlacement.END))
    }

    @Test
    fun `bad ids are rejected`() {
        assertThat(rejected("queue_add")).isEqualTo(MissingArgument("ids"))
        assertThat(rejected("queue_add", "ids" to emptyList<Long>())).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("queue_add", "ids" to "")).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("queue_add", "ids" to listOf(1L, 0L))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("queue_add", "ids" to listOf(-4L))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("queue_add", "ids" to "1,two")).isEqualTo(BadArgumentType("ids", "ids"))
        assertThat(rejected("queue_add", "ids" to listOf(1.5))).isEqualTo(BadArgumentType("ids", "ids"))
        assertThat(rejected("queue_add", "ids" to true)).isEqualTo(BadArgumentType("ids", "ids"))
    }

    @Test
    fun `too many ids are rejected`() {
        val tooMany = (1L..ApiLimits.MAX_IDS + 1L).toList()
        assertThat(rejected("queue_add", "ids" to tooMany)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(parsed("queue_add", "ids" to tooMany.take(ApiLimits.MAX_IDS))).isInstanceOf(ApiCommand.QueueAdd::class.java)
    }

    @Test
    fun `an unknown placement is rejected`() {
        assertThat(rejected("queue_add", "ids" to listOf(1L), "placement" to "middle")).isInstanceOf(BadArgumentValue::class.java)
    }

    @Test
    fun `queue indices are not negative`() {
        assertThat(parsed("queue_remove", "index" to 2)).isEqualTo(ApiCommand.QueueRemove(2))
        assertThat(parsed("queue_skip_to", "index" to 0)).isEqualTo(ApiCommand.QueueSkipTo(0))
        assertThat(parsed("queue_move", "from" to 4, "to" to 2)).isEqualTo(ApiCommand.QueueMove(4, 2))
        assertThat(rejected("queue_remove", "index" to -1)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("queue_move", "from" to 1)).isEqualTo(MissingArgument("to"))
        assertThat(rejected("queue_move", "from" to 1, "to" to -2)).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `play album, artist and playlist take an id and optional play options`() {
        assertThat(parsed("play_album", "id" to 12L)).isEqualTo(ApiCommand.PlayAlbum(12, PlayOptions()))
        assertThat(parsed("play_artist", "id" to 3, "shuffle" to true))
            .isEqualTo(ApiCommand.PlayArtist(3, PlayOptions(shuffle = true)))
        assertThat(parsed("play_playlist", "id" to "8", "start_index" to 2, "shuffle" to "false"))
            .isEqualTo(ApiCommand.PlayPlaylist(8, PlayOptions(startIndex = 2)))
    }

    @Test
    fun `play by id needs a positive id and a start index that is not negative`() {
        assertThat(rejected("play_album")).isEqualTo(MissingArgument("id"))
        assertThat(rejected("play_album", "id" to 0L)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("play_playlist", "id" to -3L)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("play_artist", "id" to 1L, "start_index" to -1)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("play_artist", "id" to 1L, "shuffle" to "maybe")).isEqualTo(BadArgumentType("shuffle", "boolean"))
    }

    @Test
    fun `play search takes plain text, trimmed`() {
        assertThat(parsed("play_search", "q" to "  daft punk ")).isEqualTo(ApiCommand.PlaySearch("daft punk", PlayOptions()))
        assertThat(parsed("play_search", "q" to "AC/DC")).isEqualTo(ApiCommand.PlaySearch("AC/DC", PlayOptions()))
        assertThat(parsed("play_search", "q" to "Artist: Song")).isEqualTo(ApiCommand.PlaySearch("Artist: Song", PlayOptions()))
    }

    @Test
    fun `play search rejects a missing, blank, long or non text query`() {
        assertThat(rejected("play_search")).isEqualTo(MissingArgument("q"))
        assertThat(rejected("play_search", "q" to "   ")).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("play_search", "q" to "x".repeat(ApiLimits.MAX_QUERY_LENGTH + 1)))
            .isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("play_search", "q" to 5)).isEqualTo(BadArgumentType("q", "string"))
    }

    @Test
    fun `play tracks takes ids and play options`() {
        assertThat(parsed("play_tracks", "ids" to listOf(5L, 6L), "start_index" to 1, "shuffle" to true))
            .isEqualTo(ApiCommand.PlayTracks(listOf(5, 6), PlayOptions(shuffle = true, startIndex = 1)))
    }

    @Test
    fun `sleep start takes minutes or end of song or end of album`() {
        assertThat(parsed("sleep_start", "minutes" to 30)).isEqualTo(ApiCommand.SleepStart(SleepTimerOption.Minutes(30)))
        assertThat(parsed("sleep_start", "mode" to "end_of_song")).isEqualTo(ApiCommand.SleepStart(SleepTimerOption.EndOfSong))
        assertThat(parsed("sleep_start", "mode" to "END_OF_ALBUM")).isEqualTo(ApiCommand.SleepStart(SleepTimerOption.EndOfAlbum))
    }

    @Test
    fun `sleep start needs exactly one of minutes and mode, within range`() {
        assertThat(rejected("sleep_start")).isEqualTo(MissingArgument("minutes"))
        assertThat(rejected("sleep_start", "minutes" to 10, "mode" to "end_of_song")).isInstanceOf(BadArgumentValue::class.java)
        assertThat(rejected("sleep_start", "mode" to "soon")).isInstanceOf(BadArgumentValue::class.java)
        assertThat(rejected("sleep_start", "minutes" to 0)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("sleep_start", "minutes" to ApiLimits.MAX_SLEEP_MINUTES + 1)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(parsed("sleep_start", "minutes" to ApiLimits.MAX_SLEEP_MINUTES))
            .isEqualTo(ApiCommand.SleepStart(SleepTimerOption.Minutes(ApiLimits.MAX_SLEEP_MINUTES)))
    }

    @Test
    fun `sleep extend takes minutes`() {
        assertThat(parsed("sleep_extend", "minutes" to 15)).isEqualTo(ApiCommand.SleepExtend(15))
        assertThat(rejected("sleep_extend")).isEqualTo(MissingArgument("minutes"))
        assertThat(rejected("sleep_extend", "minutes" to -5)).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `crossfade is in whole seconds from 0 to 12`() {
        assertThat(parsed("crossfade", "seconds" to 0)).isEqualTo(ApiCommand.Crossfade(0))
        assertThat(parsed("crossfade", "seconds" to 12)).isEqualTo(ApiCommand.Crossfade(12))
        assertThat(rejected("crossfade", "seconds" to 13)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("crossfade", "seconds" to -1)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("crossfade")).isEqualTo(MissingArgument("seconds"))
    }

    @Test
    fun `speed is a factor from 0 point 5 to 2`() {
        assertThat(parsed("speed", "speed" to 1.25)).isEqualTo(ApiCommand.Speed(1.25))
        assertThat(parsed("speed", "speed" to 1.5f)).isEqualTo(ApiCommand.Speed(1.5))
        assertThat(parsed("speed", "speed" to "0.5")).isEqualTo(ApiCommand.Speed(0.5))
        assertThat(parsed("speed", "speed" to 2)).isEqualTo(ApiCommand.Speed(2.0))
        assertThat(rejected("speed", "speed" to 0.49)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("speed", "speed" to 2.01)).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rejected("speed", "speed" to Double.NaN)).isInstanceOf(BadArgumentType::class.java)
        assertThat(rejected("speed", "speed" to "fast")).isEqualTo(BadArgumentType("speed", "number"))
        assertThat(rejected("speed")).isEqualTo(MissingArgument("speed"))
    }

    @Test
    fun `a value that looks like a URI or a file path is rejected in any argument`() {
        listOf(
            "content://media/external/audio/media/5",
            "file:///sdcard/Music/a.flac",
            "android.resource://io.sonora/raw/x",
            "https://example.com/a.mp3",
            "/sdcard/Music/a.flac",
            "~/Music",
            "C:\\Users\\a.flac",
            "..\\a",
            "a/../b",
        ).forEach { value ->
            assertThat(rejected("play_search", "q" to value)).isEqualTo(LocationRejected("q"))
        }
        assertThat(rejected("play", "uri" to "content://media/external/audio/media/5")).isEqualTo(LocationRejected("uri"))
        assertThat(rejected("queue_add", "ids" to listOf("content://x"))).isEqualTo(LocationRejected("ids"))
        assertThat(rejected("queue_add", "ids" to "file:///a")).isEqualTo(LocationRejected("ids"))
    }

    @Test
    fun `unknown extras that are not locations are ignored`() {
        assertThat(parsed("play", "note" to "hello", "count" to 3)).isEqualTo(ApiCommand.Play)
    }

    @Test
    fun `a rejection never repeats the caller value`() {
        val error = rejected("play_search", "q" to "content://secret/path")
        assertThat(error.message).doesNotContain("secret")
        assertThat(rejected("explode").message).doesNotContain("explode")
    }

    @Test
    fun `every error has a code`() {
        assertThat(
            listOf(rejected("x"), rejected("seek"), rejected("seek", "position_ms" to "a"), rejected("seek", "position_ms" to -1))
                .map { it.code },
        ).containsExactly("unknown_method", "missing_argument", "bad_argument_type", "argument_out_of_range").inOrder()
    }
}
