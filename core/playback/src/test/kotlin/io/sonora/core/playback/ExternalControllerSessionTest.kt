package io.sonora.core.playback

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.car.CarLibraryTree
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Who may make the session play a URI. Robolectric cannot run a controller from another package against a real
 * session, so the callbacks are called as the session would call them, with a [ControllerInfo] built for another
 * uid ([ControllerInfo.createTestOnlyControllerInfo]) next to one for Sonora's own uid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalControllerSessionTest {

    private class Store(var snapshot: PlaybackSnapshot? = null) : PlaybackSnapshotStore {
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: PlaybackSnapshot) { this.snapshot = snapshot }
        override suspend fun savePosition(itemId: Long, positionMs: Long) = Unit
    }

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library = FakeCarLibrary(listOf(carTrack(1, album = 10, albumTitle = "First"), carTrack(2, album = 10, albumTitle = "First")))
    private val store = Store()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var callback: SonoraLibraryCallback
    private lateinit var playback: Media3PlaybackController

    private val owner = object : SessionQueueOwner {
        override fun setShuffle(enabled: Boolean) = playback.sessionOwner.setShuffle(enabled)
        override fun expectRestore(queue: PlayQueue) = playback.sessionOwner.expectRestore(queue)
        override fun keepConnected() = playback.sessionOwner.keepConnected()
        override fun queueFor(tracks: List<Track>, startIndex: Int, shuffle: Boolean) =
            playback.sessionOwner.queueFor(tracks, startIndex, shuffle)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun controller(uid: Int, packageName: String) =
        ControllerInfo.createTestOnlyControllerInfo(packageName, 1234, uid, 1, 1, false, Bundle.EMPTY, true)

    private val sonora get() = controller(Process.myUid(), context.packageName)
    private val stranger get() = controller(Process.myUid() + 1, "com.example.other")

    /** A stranger that claims Sonora's package name, which it can put in its connection request. */
    private val impostor get() = controller(Process.myUid() + 1, context.packageName)

    @Before
    fun setUp() {
        player = SessionPlayer.build(context)
        val resumption = PlaybackResumption(store, { { true } }, { true }, Dispatchers.Unconfined)
        val car = CarBrowser(
            context, CarLibraryTree(library, FakeCarMarks(), { MutableStateFlow(emptyList()) }), scope,
            CarArtwork(context)::carUri, readable = { { true } }, work = Dispatchers.Unconfined,
        )
        callback = SonoraLibraryCallback(owner, resumption, scope, car)
        session = SonoraSession.build(context, player, callback, sessionActivity = null)
        playback = Media3PlaybackController(context, Store(), sessionToken = { session.token })
        idle()
    }

    @After
    fun tearDown() {
        scope.cancel()
        session.release()
        player.release()
        idle()
    }

    private val uri = Uri.parse("content://com.example.other/secret.flac")

    /** What a controller sends to play [uri]: the item loses its local configuration over IPC, the request metadata stays. */
    private fun uriItem() = MediaItem.Builder()
        .setMediaId("whatever")
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .build()

    private fun addedBy(controller: ControllerInfo, item: MediaItem): Result<List<MediaItem>> {
        val future = callback.onAddMediaItems(session, controller, mutableListOf(item))
        idle()
        return runCatching { future.get() }
    }

    private fun setBy(controller: ControllerInfo, item: MediaItem): Result<MediaSession.MediaItemsWithStartPosition> {
        val future = callback.onSetMediaItems(session, controller, mutableListOf(item), 0, 0L)
        idle()
        return runCatching { future.get() }
    }

    @Test
    fun `a URI an external controller supplies is not played`() {
        assertThat(addedBy(stranger, uriItem()).isFailure).isTrue()
        assertThat(setBy(stranger, uriItem()).isFailure).isTrue()
    }

    @Test
    fun `an item that already carries a local configuration is not trusted from an external controller either`() {
        val item = MediaItem.Builder().setMediaId("whatever").setUri(uri).build()

        assertThat(addedBy(stranger, item).isFailure).isTrue()
    }

    @Test
    fun `a package name is not enough to be Sonora`() {
        assertThat(addedBy(impostor, uriItem()).isFailure).isTrue()
    }

    @Test
    fun `a rejected request holds back the play the controller queued behind it`() {
        setBy(stranger, uriItem())

        assertThat(callback.failedRequests.holds(stranger, ends = false)).isTrue()
    }

    @Test
    fun `Sonora's own controller still gets the URI it supplied`() {
        val added = addedBy(sonora, uriItem()).getOrThrow()

        assertThat(added.single().localConfiguration!!.uri).isEqualTo(uri)
    }

    @Test
    fun `Sonora's own controller still sets items through the library callback`() {
        val set = setBy(sonora, uriItem()).getOrThrow()

        assertThat(set.mediaItems.single().localConfiguration!!.uri).isEqualTo(uri)
    }

    @Test
    fun `an external car song id still plays its list`() {
        val set = setBy(stranger, MediaItem.Builder().setMediaId("song/2/1@album/10").build()).getOrThrow()

        assertThat(set.mediaItems.map { QueueMediaId.parse(it.mediaId).trackId }).containsExactly(1L, 2L).inOrder()
        assertThat(set.startIndex).isEqualTo(1)
        assertThat(set.mediaItems.all { it.localConfiguration != null }).isTrue()
    }

    @Test
    fun `an external voice request still finds music`() {
        val request = MediaItem.RequestMetadata.Builder().setSearchQuery("").build()

        val set = setBy(stranger, MediaItem.Builder().setRequestMetadata(request).build()).getOrThrow()

        assertThat(set.mediaItems.map { QueueMediaId.parse(it.mediaId).trackId }).containsExactly(1L, 2L)
    }

    @Test
    fun `a voice request that also names a URI is not a voice request, from an external controller`() {
        val request = MediaItem.RequestMetadata.Builder().setSearchQuery("x").setMediaUri(uri).build()

        assertThat(setBy(stranger, MediaItem.Builder().setRequestMetadata(request).build()).isFailure).isTrue()
    }

    @Test
    fun `an external controller can still resume the saved queue`() {
        val queue = PlayQueue().play(library.tracks.value, 1).queue
        store.snapshot = PlaybackSnapshot(
            items = queue.items, currentIndex = queue.currentIndex, positionMs = 0, shuffled = false,
            unshuffledIds = queue.items.map { it.id }, repeatMode = RepeatMode.OFF,
        )

        val future = callback.onPlaybackResumption(session, stranger, true)
        idle()

        val resumed = future.get()
        assertThat(resumed.mediaItems).hasSize(2)
        assertThat(resumed.startIndex).isEqualTo(1)
    }
}
