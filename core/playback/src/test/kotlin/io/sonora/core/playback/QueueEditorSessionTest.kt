package io.sonora.core.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.QueueChange
import kotlin.random.Random
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The queue editor against a real ExoPlayer in a real MediaSession, driven through a real MediaController (the
 * production wiring, minus the service). Player events re-sync the queue as the app does, so commands, masking and
 * session round trips interleave like on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueEditorSessionTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private val editor = QueueEditor(Random(3))
    private val externals = mutableListOf<MediaController>()

    private fun track(id: Long) = Track(
        id = id, uri = "content://io.sonora.test/doc/$id", title = "T$id", artist = "A", album = "B",
        albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        player = SessionPlayer.build(context)
        session = MediaSession.Builder(context, player).setCallback(UriResolvingCallback).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        idle()
        controller = future.get()
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!editor.editing) editor.followPlayer(player)
            }
        })
    }

    @After
    fun tearDown() {
        externals.forEach { it.release() }
        controller.release()
        session.release()
        player.release()
        idle()
    }

    /** Runs a queue operation like the app does, then lets the session catch up. */
    private fun run(startPlayback: Boolean = false, operation: PlayQueue.() -> QueueChange) {
        editor.edit(controller, startPlayback, operation)
        idle()
    }

    /** Another controller on the same session, like the notification, a Bluetooth headset or Android Auto. */
    private fun externalController(): MediaController {
        val future = MediaController.Builder(context, session.token).buildAsync()
        idle()
        return future.get().also { externals += it }
    }

    private fun foreignItem(mediaId: String) =
        MediaItem.Builder().setMediaId(mediaId).setUri("content://io.sonora.test/doc/$mediaId").build()

    private fun trackIds(p: Player) = List(p.mediaItemCount) { QueueMediaId.parse(p.getMediaItemAt(it).mediaId).trackId }

    private fun assertInStep(expectedSize: Int) {
        val queueIds = editor.sync(controller).items.map { it.track.id }
        assertThat(queueIds).hasSize(expectedSize)
        assertThat(trackIds(controller)).isEqualTo(queueIds)
        assertThat(trackIds(player)).isEqualTo(queueIds)
        assertThat(player.currentMediaItemIndex).isEqualTo(editor.queue.currentIndex)
    }

    @Test
    fun `a retagged track shows its new details in the session without changing what plays`() {
        run(startPlayback = true) { play((1L..3L).map(::track), startIndex = 1) }
        run { addToQueue(listOf(track(2))) }

        run { updateTrack(track(2).copy(title = "Retagged")) }

        val titles = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaMetadata.title.toString() }
        assertThat(titles).containsExactly("T1", "Retagged", "T3", "Retagged").inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(editor.sync(controller).items.map { it.track.title }).containsExactly("T1", "Retagged", "T3", "Retagged").inOrder()
        assertInStep(4)
    }

    @Test
    fun `emulator sequence keeps every entry in the session playlist`() {
        run(startPlayback = true) { playNow((1L..24L).map(::track)) }
        run { addToQueue((101L..104L).map(::track)) }
        controller.repeatMode = Player.REPEAT_MODE_ONE
        idle()
        assertInStep(28)

        run { setShuffle(true, Random(42)) }
        assertInStep(28)
        run { remove(currentIndex + 1) }
        assertInStep(27)
        run(startPlayback = true) { skipTo(currentIndex + 2) }
        assertInStep(27)
        run { move(currentIndex + 2, currentIndex + 4) }
        assertInStep(27)
        run { setShuffle(false) }
        assertInStep(27)
    }

    @Test
    fun `commands issued back to back before the session catches up keep every entry`() {
        run { playNow((1L..24L).map(::track)) }
        editor.edit(controller, false) { addToQueue((101L..104L).map(::track)) }
        editor.edit(controller, false) { setShuffle(true, Random(7)) }
        editor.edit(controller, false) { remove(currentIndex + 1) }
        editor.edit(controller, false) { skipTo(currentIndex + 2) }
        editor.edit(controller, false) { move(currentIndex + 2, currentIndex + 5) }
        editor.edit(controller, false) { setShuffle(false) }
        idle()

        assertInStep(27)
    }

    /**
     * The emulator report (rows around a dragged row gone from Up Next, session queue 20 instead of 27) matches a
     * stray jump to the dropped row: nothing leaves the playlist, the rows it passed just become history.
     */
    @Test
    fun `a jump to the dropped row explains the emulator observation without losing entries`() {
        run(startPlayback = true) { playNow((1L..24L).map(::track)) }
        run { addToQueue((101L..104L).map(::track)) }
        run { setShuffle(true, Random(42)) }
        run { remove(currentIndex + 1) }
        run(startPlayback = true) { skipTo(currentIndex + 2) }
        val dragged = editor.queue.items[editor.queue.currentIndex + 2].id
        run { move(currentIndex + 2, currentIndex + 4) }

        run(startPlayback = true) { skipTo(items.indexOfFirst { it.id == dragged }) }

        assertInStep(27)
        assertThat(editor.queue.current?.id).isEqualTo(dragged)
        assertThat(editor.queue.items.size - editor.queue.currentIndex - 1).isEqualTo(20)
    }

    @Test
    fun `shuffle turned on by another controller reorders the real queue around the current track`() {
        run(startPlayback = true) { play((1L..10L).map(::track), startIndex = 3) }
        val external = externalController()

        external.shuffleModeEnabled = true
        idle()

        assertInStep(10)
        assertThat(editor.queue.shuffled).isTrue()
        assertThat(editor.queue.currentIndex).isEqualTo(0)
        assertThat(editor.queue.current?.track?.id).isEqualTo(4L)
        assertThat(trackIds(player)).isNotEqualTo((1L..10L).toList())
        // The flag is shown everywhere but Media3 plays the playlist as listed: no hidden shuffle order.
        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(external.shuffleModeEnabled).isTrue()
        assertThat(player.nextMediaItemIndex).isEqualTo(player.currentMediaItemIndex + 1)

        external.shuffleModeEnabled = false
        idle()

        assertInStep(10)
        assertThat(editor.queue.shuffled).isFalse()
        assertThat(trackIds(player)).isEqualTo((1L..10L).toList())
        assertThat(editor.queue.current?.track?.id).isEqualTo(4L)
    }

    @Test
    fun `shuffle turned on in the app shows on other controllers without a hidden play order`() {
        run(startPlayback = true) { play((1L..10L).map(::track)) }
        val external = externalController()

        run { setShuffle(true, Random(5)) }

        assertInStep(10)
        assertThat(external.shuffleModeEnabled).isTrue()
        assertThat(player.nextMediaItemIndex).isEqualTo(player.currentMediaItemIndex + 1)
        assertThat(external.nextMediaItemIndex).isEqualTo(external.currentMediaItemIndex + 1)
    }

    @Test
    fun `shuffle turned on with nothing queued is kept for the next tracks`() {
        val external = externalController()
        run { setShuffle(true) }

        assertThat(external.shuffleModeEnabled).isTrue()
        run(startPlayback = true) { addToQueue((1L..10L).map(::track)) }

        assertInStep(10)
        assertThat(editor.queue.shuffled).isTrue()
        assertThat(editor.queue.setShuffle(false).queue.items.map { it.track.id }).isEqualTo((1L..10L).toList())
    }

    @Test
    fun `session entries without an item id keep their ids when other entries come and go`() {
        player.setMediaItems(listOf(foreignItem("a"), foreignItem("b"), foreignItem("a"), foreignItem("c")))
        idle()
        val before = editor.sync(controller).items.map { it.id }
        assertThat(before.toSet()).hasSize(4)

        player.addMediaItem(0, foreignItem("z"))
        player.removeMediaItem(2) // the first "b"
        idle()
        val after = editor.sync(controller).items

        assertThat(after).hasSize(4)
        assertThat(after.map { it.id }.drop(1)).containsExactly(before[0], before[2], before[3]).inOrder()
        assertThat(after[0].id).isNotIn(before)
    }
}
