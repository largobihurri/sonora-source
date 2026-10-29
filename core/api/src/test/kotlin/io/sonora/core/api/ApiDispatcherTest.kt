package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError.ArgumentOutOfRange
import io.sonora.core.api.ApiError.BadArgumentValue
import io.sonora.core.api.ApiError.NotFound
import io.sonora.core.api.ApiError.NothingToDo
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ApiDispatcherTest {

    private val library = listOf(
        track(1, albumId = 10, artistId = 5, trackNumber = 2),
        track(2, albumId = 10, artistId = 5, trackNumber = 1),
        track(3, albumId = 11, artistId = 5),
        track(4, albumId = 12, artistId = 6, title = "Needle"),
    )

    private fun rig(playback: FakePlayback = FakePlayback()) =
        Rig(playback = playback, library = FakeLibrary(tracks = library))

    private fun run(rig: Rig, command: ApiCommand) = runBlocking { rig.dispatcher.execute(command) }

    private fun call(rig: Rig, method: String, vararg extras: Pair<String, Any?>) =
        runBlocking { rig.dispatcher.call(method, mapOf(*extras)) }

    private fun failure(result: ApiResult): ApiError {
        assertThat(result).isInstanceOf(ApiResult.Failed::class.java)
        return (result as ApiResult.Failed).error
    }

    private fun payload(result: ApiResult): Map<String, Any> {
        assertThat(result).isInstanceOf(ApiResult.Ok::class.java)
        return (result as ApiResult.Ok).payload
    }

    @Test
    fun `transport commands go to the player`() {
        val rig = rig()
        listOf(ApiCommand.Play, ApiCommand.Pause, ApiCommand.Toggle, ApiCommand.Next, ApiCommand.Previous).forEach {
            assertThat(run(rig, it).ok).isTrue()
        }
        run(rig, ApiCommand.Seek(42_000))
        run(rig, ApiCommand.Shuffle(true))
        run(rig, ApiCommand.Repeat(RepeatMode.ONE))
        assertThat(rig.playback.calls)
            .containsExactly("play", "pause", "toggle", "next", "previous", "seek 42000", "shuffle true", "repeat ONE")
            .inOrder()
    }

    @Test
    fun `an ok result has an empty payload when there is nothing to report`() {
        assertThat(payload(run(rig(), ApiCommand.Play)).isEmpty()).isTrue()
    }

    @Test
    fun `queue add resolves ids in the order given and maps the placement`() {
        val rig = rig()
        assertThat(payload(run(rig, ApiCommand.QueueAdd(listOf(3, 1, 3), QueuePlacement.END)))).containsEntry("count", 3)
        run(rig, ApiCommand.QueueAdd(listOf(2), QueuePlacement.NEXT))
        run(rig, ApiCommand.QueueAdd(listOf(4), QueuePlacement.NOW))
        assertThat(rig.playback.calls).containsExactly("addToQueue[3,1,3]", "playNext[2]", "playNow[4]").inOrder()
    }

    @Test
    fun `queue add with an unknown id changes nothing`() {
        val rig = rig()
        val result = run(rig, ApiCommand.QueueAdd(listOf(1, 99), QueuePlacement.END))
        assertThat(failure(result)).isEqualTo(NotFound("track", 99))
        assertThat(rig.playback.calls).isEmpty()
    }

    @Test
    fun `queue remove refuses the current item and indices outside the queue`() {
        val rig = rig(FakePlayback(playbackOf(1, 2, 3, current = 1)))
        assertThat(failure(run(rig, ApiCommand.QueueRemove(1)))).isInstanceOf(BadArgumentValue::class.java)
        assertThat(failure(run(rig, ApiCommand.QueueRemove(3)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rig.playback.calls).isEmpty()
        assertThat(run(rig, ApiCommand.QueueRemove(2)).ok).isTrue()
        assertThat(run(rig, ApiCommand.QueueRemove(0)).ok).isTrue()
        assertThat(rig.playback.calls).containsExactly("remove 2", "remove 0").inOrder()
    }

    @Test
    fun `queue move only moves upcoming items inside the queue`() {
        val rig = rig(FakePlayback(playbackOf(1, 2, 3, 4, current = 1)))
        assertThat(failure(run(rig, ApiCommand.QueueMove(1, 3)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(failure(run(rig, ApiCommand.QueueMove(3, 0)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(failure(run(rig, ApiCommand.QueueMove(2, 4)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rig.playback.calls).isEmpty()
        assertThat(run(rig, ApiCommand.QueueMove(3, 2)).ok).isTrue()
        assertThat(rig.playback.calls).containsExactly("move 3->2")
    }

    @Test
    fun `queue skip to needs an index in the queue and clear upcoming clears`() {
        val rig = rig(FakePlayback(playbackOf(1, 2, 3)))
        assertThat(failure(run(rig, ApiCommand.QueueSkipTo(3)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(run(rig, ApiCommand.QueueSkipTo(2)).ok).isTrue()
        assertThat(run(rig, ApiCommand.QueueClearUpcoming).ok).isTrue()
        assertThat(rig.playback.calls).containsExactly("skipTo 2", "clearUpcoming").inOrder()
    }

    @Test
    fun `queue edits on an empty queue are out of range`() {
        val rig = rig()
        assertThat(failure(run(rig, ApiCommand.QueueSkipTo(0)))).isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(failure(run(rig, ApiCommand.QueueRemove(0)))).isInstanceOf(ArgumentOutOfRange::class.java)
    }

    @Test
    fun `play album plays its tracks in album order`() {
        val rig = rig()
        val result = run(rig, ApiCommand.PlayAlbum(10, PlayOptions(shuffle = true, startIndex = 1)))
        assertThat(payload(result)).containsEntry("count", 2)
        assertThat(rig.playback.calls).containsExactly("playQueue[2,1] start=1 shuffle=true")
    }

    @Test
    fun `play album that does not exist is not found`() {
        val rig = rig()
        assertThat(failure(run(rig, ApiCommand.PlayAlbum(77, PlayOptions())))).isEqualTo(NotFound("album", 77))
        assertThat(rig.playback.calls).isEmpty()
    }

    @Test
    fun `play artist plays the artist's songs`() {
        val rig = rig()
        run(rig, ApiCommand.PlayArtist(5, PlayOptions()))
        assertThat(rig.playback.calls).containsExactly("playQueue[1,2,3] start=0 shuffle=false")
        assertThat(failure(run(rig, ApiCommand.PlayArtist(77, PlayOptions())))).isEqualTo(NotFound("artist", 77))
    }

    @Test
    fun `play playlist plays the available entries and skips files no longer in the library`() {
        val gone = track(500)
        val rig = Rig(
            library = FakeLibrary(tracks = library),
            playlists = FakePlaylists(listOf(playlistOf(8, "Mix", library[3], gone, library[0]), playlistOf(9, "Empty", gone))),
        )
        assertThat(payload(run(rig, ApiCommand.PlayPlaylist(8, PlayOptions())))).containsEntry("count", 2)
        assertThat(rig.playback.calls).containsExactly("playQueue[4,1] start=0 shuffle=false")
        assertThat(failure(run(rig, ApiCommand.PlayPlaylist(77, PlayOptions())))).isEqualTo(NotFound("playlist", 77))
        assertThat(failure(run(rig, ApiCommand.PlayPlaylist(9, PlayOptions())))).isInstanceOf(NothingToDo::class.java)
    }

    @Test
    fun `play search plays the matches and says when there are none`() {
        val rig = rig()
        run(rig, ApiCommand.PlaySearch("needle", PlayOptions()))
        assertThat(rig.playback.calls).containsExactly("playQueue[4] start=0 shuffle=false")
        assertThat(failure(run(rig, ApiCommand.PlaySearch("zzz", PlayOptions())))).isInstanceOf(NothingToDo::class.java)
    }

    @Test
    fun `play tracks keeps the order and repeats, and fails whole on an unknown id`() {
        val rig = rig()
        run(rig, ApiCommand.PlayTracks(listOf(4, 2, 4), PlayOptions(startIndex = 2)))
        assertThat(rig.playback.calls).containsExactly("playQueue[4,2,4] start=2 shuffle=false")
        assertThat(failure(run(rig, ApiCommand.PlayTracks(listOf(4, 99), PlayOptions())))).isEqualTo(NotFound("track", 99))
        assertThat(rig.playback.calls).hasSize(1)
    }

    @Test
    fun `a start index past the list is out of range`() {
        val rig = rig()
        assertThat(failure(run(rig, ApiCommand.PlayTracks(listOf(1, 2), PlayOptions(startIndex = 2)))))
            .isInstanceOf(ArgumentOutOfRange::class.java)
        assertThat(rig.playback.calls).isEmpty()
    }

    @Test
    fun `sleep timer starts, extends and cancels`() {
        val rig = rig()
        assertThat(failure(run(rig, ApiCommand.SleepExtend(10)))).isInstanceOf(NothingToDo::class.java)
        run(rig, ApiCommand.SleepStart(SleepTimerOption.Minutes(30)))
        run(rig, ApiCommand.SleepExtend(10))
        run(rig, ApiCommand.SleepCancel)
        run(rig, ApiCommand.SleepStart(SleepTimerOption.EndOfAlbum))
        assertThat(rig.sleepTimer.calls)
            .containsExactly("start Minutes(minutes=30)", "extend 10", "cancel", "start EndOfAlbum").inOrder()
    }

    @Test
    fun `sleep extend works while a timer runs`() {
        val rig = Rig(sleepTimer = FakeSleepTimer(SleepTimerState(SleepTimerOption.EndOfSong, remainingMs = 5_000)))
        assertThat(run(rig, ApiCommand.SleepExtend(5)).ok).isTrue()
        assertThat(rig.sleepTimer.calls).containsExactly("extend 5")
    }

    @Test
    fun `crossfade is stored and reported`() {
        val rig = rig()
        assertThat(payload(run(rig, ApiCommand.Crossfade(6)))).containsEntry("seconds", 6)
        assertThat(rig.crossfade.current).isEqualTo(6)
    }

    @Test
    fun `speed snaps to the tempo grid and reports the speed applied`() {
        val rig = rig()
        assertThat(payload(run(rig, ApiCommand.Speed(1.27)))).containsEntry("speed", 1.25)
        assertThat(rig.tempo.current.speedBasisPoints).isEqualTo(12_500)
        assertThat(payload(run(rig, ApiCommand.Speed(2.0)))).containsEntry("speed", 2.0)
        assertThat(rig.tempo.current.speedBasisPoints).isEqualTo(PlaybackTempo.MAX_SPEED_BASIS_POINTS)
    }

    @Test
    fun `dj mode is switched`() {
        val rig = rig()
        run(rig, ApiCommand.DjMode(true))
        assertThat(rig.djMode.enabled.value).isTrue()
        run(rig, ApiCommand.DjMode(false))
        assertThat(rig.djMode.enabled.value).isFalse()
    }

    @Test
    fun `call parses and runs, and a parse error is a failed result`() {
        val rig = rig()
        assertThat(call(rig, "pause").ok).isTrue()
        assertThat(rig.playback.calls).containsExactly("pause")
        assertThat(failure(call(rig, "explode"))).isEqualTo(ApiError.UnknownMethod("explode"))
        assertThat(failure(call(rig, "seek"))).isEqualTo(ApiError.MissingArgument("position_ms"))
        assertThat(failure(call(rig, "play_search", "q" to "content://media/1"))).isEqualTo(ApiError.LocationRejected("q"))
        assertThat(rig.playback.calls).hasSize(1)
    }

    @Test
    fun `a port that throws becomes a failure that does not leak the cause`() {
        val playback = FakePlayback().apply { failWith = IllegalStateException("/storage/emulated/0/secret.flac") }
        val result = run(rig(playback), ApiCommand.Play)
        assertThat(failure(result)).isEqualTo(ApiError.Failed)
        assertThat((failure(result)).message).doesNotContain("secret")
    }

    @Test
    fun `every method of the API has a command the dispatcher runs`() {
        val rig = Rig(
            playback = FakePlayback(playbackOf(1, 2, 3)),
            sleepTimer = FakeSleepTimer(SleepTimerState(SleepTimerOption.EndOfSong, remainingMs = null)),
            library = FakeLibrary(tracks = library),
            playlists = FakePlaylists(listOf(playlistOf(8, "Mix", library[0]))),
        )
        val samples = mapOf<String, Map<String, Any?>>(
            "play" to emptyMap(), "pause" to emptyMap(), "toggle" to emptyMap(), "next" to emptyMap(),
            "previous" to emptyMap(), "seek" to mapOf("position_ms" to 1),
            "shuffle" to mapOf("enabled" to true), "repeat" to mapOf("mode" to "all"),
            "queue_add" to mapOf("ids" to "1"), "queue_remove" to mapOf("index" to 2),
            "queue_move" to mapOf("from" to 1, "to" to 2), "queue_clear_upcoming" to emptyMap(),
            "queue_skip_to" to mapOf("index" to 1), "play_album" to mapOf("id" to 10),
            "play_artist" to mapOf("id" to 5), "play_playlist" to mapOf("id" to 8),
            "play_search" to mapOf("q" to "Title"), "play_tracks" to mapOf("ids" to "1,2"),
            "sleep_start" to mapOf("minutes" to 5), "sleep_extend" to mapOf("minutes" to 5),
            "sleep_cancel" to emptyMap(), "crossfade" to mapOf("seconds" to 3), "speed" to mapOf("speed" to 1.1),
            "dj_mode" to mapOf("enabled" to true),
        )
        assertThat(samples.keys).containsExactlyElementsIn(ApiMethod.entries.map { it.wireName })
        samples.forEach { (method, extras) ->
            assertThat(runBlocking { rig.dispatcher.call(method, extras) }.ok).isTrue()
        }
    }
}
