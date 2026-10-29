package io.sonora.core.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The session as [PlaybackService] builds it: a real ExoPlayer in a real MediaSession with Sonora's callback, and the
 * process's [Media3PlaybackController] as the only queue owner, with no screen involved (like after a Bluetooth
 * resumption). Other controllers stand for the system media controls, a headset or Android Auto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SonoraSessionTest {

    private class FakeStore(var snapshot: PlaybackSnapshot? = null) : PlaybackSnapshotStore {
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: PlaybackSnapshot) { this.snapshot = snapshot }
        override suspend fun savePosition(itemId: Long, positionMs: Long) = Unit
    }

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = FakeStore()
    private val unreadable = mutableSetOf<Long>()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var callback: SonoraSessionCallback
    private lateinit var playback: Media3PlaybackController
    private val externals = mutableListOf<MediaController>()

    /** Late-bound: the controller connects to the session, so the session is built first. */
    private val owner = object : SessionQueueOwner {
        override fun setShuffle(enabled: Boolean) = playback.sessionOwner.setShuffle(enabled)
        override fun expectRestore(queue: PlayQueue) = playback.sessionOwner.expectRestore(queue)
        override fun keepConnected() = playback.sessionOwner.keepConnected()
    }

    private fun track(id: Long) = Track(
        id = id, uri = "content://io.sonora.test/doc/$id", title = "T$id", artist = "A", album = "B",
        albumArtist = null, durationMs = 300_000, trackNumber = null, discNumber = null, year = null, albumId = 1,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 1, sampleRateHz = 96_000, bitsPerSample = 24,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        player = SessionPlayer.build(context)
        val resumption = PlaybackResumption(
            store = store,
            accessFilter = { { it.id !in unreadable } },
            exists = { it.id !in unreadable },
            io = Dispatchers.Unconfined,
        )
        callback = SonoraSessionCallback(owner, resumption, scope)
        session = SonoraSession.build(context, player, callback, sessionActivity = null)
        playback = Media3PlaybackController(context, FakeStore(), sessionToken = { session.token })
        idle()
    }

    @After
    fun tearDown() {
        externals.forEach { it.release() }
        scope.cancel()
        session.release()
        player.release()
        idle()
    }

    private fun externalController(): MediaController {
        val future = MediaController.Builder(context, session.token).buildAsync()
        idle()
        return future.get().also { externals += it }
    }

    private fun trackIds() = List(player.mediaItemCount) { QueueMediaId.parse(player.getMediaItemAt(it).mediaId).trackId }

    private fun currentTrackId() = QueueMediaId.parse(player.currentMediaItem!!.mediaId).trackId

    private fun icons(buttons: List<CommandButton>) = buttons.map { it.icon }

    @Test
    fun `the media controls offer shuffle and repeat buttons that follow the player`() {
        val external = externalController()
        assertThat(icons(session.mediaButtonPreferences))
            .containsExactly(CommandButton.ICON_SHUFFLE_OFF, CommandButton.ICON_REPEAT_OFF).inOrder()

        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ONE
        idle()

        assertThat(icons(session.mediaButtonPreferences))
            .containsExactly(CommandButton.ICON_SHUFFLE_ON, CommandButton.ICON_REPEAT_ONE).inOrder()
        assertThat(icons(external.mediaButtonPreferences))
            .containsExactly(CommandButton.ICON_SHUFFLE_ON, CommandButton.ICON_REPEAT_ONE).inOrder()
        assertThat(external.isSessionCommandAvailable(MediaButtons.SHUFFLE)).isTrue()
        assertThat(external.isSessionCommandAvailable(MediaButtons.REPEAT)).isTrue()
    }

    @Test
    fun `the shuffle button reorders the real queue around the current track and back`() {
        playback.playQueue((1L..10L).map(::track), startIndex = 3)
        idle()
        val external = externalController()

        external.sendCustomCommand(MediaButtons.SHUFFLE, Bundle.EMPTY)
        idle()

        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(currentTrackId()).isEqualTo(4L)
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(trackIds()).isNotEqualTo((1L..10L).toList())
        assertThat(playback.state.value.queue.map { it.track.id }).isEqualTo(trackIds())
        assertThat(icons(external.mediaButtonPreferences).first()).isEqualTo(CommandButton.ICON_SHUFFLE_ON)

        external.sendCustomCommand(MediaButtons.SHUFFLE, Bundle.EMPTY)
        idle()

        assertThat(player.shuffleModeEnabled).isFalse()
        assertThat(trackIds()).isEqualTo((1L..10L).toList())
        assertThat(currentTrackId()).isEqualTo(4L)
    }

    @Test
    fun `the repeat button cycles off, all, one and off`() {
        val external = externalController()
        val seen = List(4) {
            external.sendCustomCommand(MediaButtons.REPEAT, Bundle.EMPTY)
            idle()
            player.repeatMode to icons(external.mediaButtonPreferences).last()
        }

        assertThat(seen).containsExactly(
            Player.REPEAT_MODE_ALL to CommandButton.ICON_REPEAT_ALL,
            Player.REPEAT_MODE_ONE to CommandButton.ICON_REPEAT_ONE,
            Player.REPEAT_MODE_OFF to CommandButton.ICON_REPEAT_OFF,
            Player.REPEAT_MODE_ALL to CommandButton.ICON_REPEAT_ALL,
        ).inOrder()
    }

    @Test
    fun `shuffle from another controller reorders the real queue with only the service's queue owner`() {
        playback.playQueue((1L..10L).map(::track), startIndex = 6)
        idle()
        val headset = externalController()

        headset.shuffleModeEnabled = true
        idle()

        assertThat(currentTrackId()).isEqualTo(7L)
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(trackIds()).isNotEqualTo((1L..10L).toList())
        assertThat(player.nextMediaItemIndex).isEqualTo(1)
    }

    /** Items 10..50 hold tracks 1..5; shuffled play order 3, 1, 5, 2, 4 playing track 5 at 30 s. */
    private val saved = PlaybackSnapshot(
        items = listOf(3L, 1L, 5L, 2L, 4L).map { QueueItem(it * 10, track(it)) },
        currentIndex = 2,
        positionMs = 30_000,
        shuffled = true,
        unshuffledIds = listOf(10L, 20L, 30L, 40L, 50L),
        repeatMode = RepeatMode.ALL,
    )

    @Test
    fun `play on an empty session resumes the saved queue without unreadable entries`() {
        store.snapshot = saved
        unreadable += 1L
        val headset = externalController()

        headset.play()
        idle()

        assertThat(trackIds()).containsExactly(3L, 5L, 2L, 4L).inOrder()
        assertThat(currentTrackId()).isEqualTo(5L)
        assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(player.playWhenReady).isTrue()
        // The queue owner took the saved restore order: shuffle off brings it back.
        playback.setShuffle(false)
        idle()
        assertThat(trackIds()).containsExactly(2L, 3L, 4L, 5L).inOrder()
        assertThat(currentTrackId()).isEqualTo(5L)
    }

    @Test
    fun `nothing saved leaves an empty session as it is`() {
        val headset = externalController()

        headset.play()
        idle()

        assertThat(player.mediaItemCount).isEqualTo(0)
    }

    @Test
    fun `at start the saved queue is shown paused at its position, with full track details`() {
        store.snapshot = saved

        callback.restoreIfEmpty(player)
        idle()

        assertThat(trackIds()).containsExactly(3L, 1L, 5L, 2L, 4L).inOrder()
        assertThat(currentTrackId()).isEqualTo(5L)
        assertThat(player.currentPosition).isEqualTo(30_000L)
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        val state = playback.state.value
        assertThat(state.currentTrack).isEqualTo(track(5))
        assertThat(state.shuffleEnabled).isTrue()
        assertThat(state.repeatMode).isEqualTo(RepeatMode.ALL)
        assertThat(state.isPlaying).isFalse()
    }

    @Test
    fun `new details for the paused restored entry show without preparing the player`() {
        store.snapshot = saved
        callback.restoreIfEmpty(player)
        idle()
        val retagged = track(5).copy(title = "Retagged")

        playback.update(retagged)
        idle()

        assertThat(playback.state.value.currentTrack).isEqualTo(retagged)
        assertThat(currentTrackId()).isEqualTo(5L)
        // Nothing was read from the file yet, so nothing is re-read: the player stays as it was, paused at 30 s.
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isEqualTo(30_000L)
    }

    @Test
    fun `tracks appended to a paused queue are published at once`() {
        playback.playQueue(listOf(track(1)))
        idle()
        playback.pause()
        idle()

        for (id in 2L..4L) {
            playback.addToQueue(listOf(track(id)))
            idle()
            assertThat(playback.state.value.queue.map { it.track.id }).isEqualTo((1L..id).toList())
        }

        val state = playback.state.value
        assertThat(state.isPlaying).isFalse()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.currentTrack?.id).isEqualTo(1L)
    }

    @Test
    fun `tracks appended to the restored paused queue are published at once`() {
        store.snapshot = saved
        callback.restoreIfEmpty(player)
        idle()

        playback.addToQueue(listOf(track(6)))
        idle()

        val state = playback.state.value
        assertThat(state.queue.map { it.track.id }).containsExactly(3L, 1L, 5L, 2L, 4L, 6L).inOrder()
        assertThat(state.currentTrack).isEqualTo(track(5))
        assertThat(state.currentIndex).isEqualTo(2)
    }

    @Test
    fun `the start restore never replaces a queue that is already playing`() {
        store.snapshot = saved
        playback.playQueue((1L..2L).map(::track))
        idle()

        callback.restoreIfEmpty(player)
        idle()

        assertThat(trackIds()).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `the resumed queue maps to media items at the saved entry and position`() {
        val items = PlaybackResumption.mediaItems(saved)

        assertThat(items.mediaItems.map { QueueMediaId.parse(it.mediaId) })
            .isEqualTo(saved.items.map { QueueMediaId.Parsed(it.track.id, it.id) })
        assertThat(items.mediaItems.first().localConfiguration?.uri.toString()).isEqualTo("content://io.sonora.test/doc/3")
        assertThat(items.startIndex).isEqualTo(2)
        assertThat(items.startPositionMs).isEqualTo(30_000L)
    }
}
