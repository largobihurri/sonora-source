package io.sonora.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.car.CarLibraryTree
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The session as a media library: a [MediaBrowser] stands for Android Auto (or any browsing client) connecting to the
 * session [PlaybackService] runs, over a real [CarLibraryTree] on an in-memory library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibrarySessionTest {

    private class EmptyStore : PlaybackSnapshotStore {
        override suspend fun load(): PlaybackSnapshot? = null
        override suspend fun save(snapshot: PlaybackSnapshot) = Unit
        override suspend fun savePosition(itemId: Long, positionMs: Long) = Unit
    }

    private val context = RuntimeEnvironment.getApplication()
    private val cacheDir = context.cacheDir.canonicalPath
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library = FakeCarLibrary(
        listOf(
            carTrack(1, "Intro", album = 10, albumTitle = "First", track = 1),
            carTrack(2, "Second Song", album = 10, albumTitle = "First", track = 2),
            carTrack(3, "Other", album = 20, albumTitle = "Late").copy(artworkUri = "file://$cacheDir/artwork/0a1b.jpg"),
        ),
    )
    private val marks = FakeCarMarks()
    private val unreadable = mutableSetOf<Long>()
    private val playlists = MutableStateFlow(emptyList<ResolvedPlaylist>())
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var car: CarBrowser
    private lateinit var playback: Media3PlaybackController
    private val browsers = mutableListOf<MediaBrowser>()

    /** The car's library work, run at once (so the test stays on one thread), noting that it ran there. */
    private class InlineWork : CoroutineDispatcher() {
        var running = false
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            val outer = running
            running = true
            try {
                block.run()
            } finally {
                running = outer
            }
        }
    }

    private val work = InlineWork()

    /** For each readability check: whether it ran as library work, off the session's thread. */
    private val readChecks = mutableListOf<Boolean>()

    /** Late-bound: the app's queue owner connects to the session, so the session is built first. */
    private val owner = object : SessionQueueOwner {
        override fun setShuffle(enabled: Boolean) = playback.sessionOwner.setShuffle(enabled)
        override fun expectRestore(queue: PlayQueue) = playback.sessionOwner.expectRestore(queue)
        override fun keepConnected() = playback.sessionOwner.keepConnected()
        override fun queueFor(tracks: List<Track>, startIndex: Int, shuffle: Boolean) =
            playback.sessionOwner.queueFor(tracks, startIndex, shuffle)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        player = SessionPlayer.build(context)
        val resumption = PlaybackResumption(EmptyStore(), { { true } }, { true }, Dispatchers.Unconfined)
        val tree = CarLibraryTree(library, marks, { playlists })
        car = CarBrowser(context, tree, scope, CarArtwork(context)::carUri, readable = {
            { track -> readChecks += work.running; track.id !in unreadable }
        }, work = work)
        val callback = SonoraLibraryCallback(owner, resumption, scope, car)
        val openApp = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_MAIN), PendingIntent.FLAG_IMMUTABLE)
        session = SonoraSession.build(context, player, callback, sessionActivity = openApp)
        playback = Media3PlaybackController(context, EmptyStore(), sessionToken = { session.token })
        idle()
    }

    @After
    fun tearDown() {
        browsers.forEach { it.release() }
        scope.cancel()
        session.release()
        player.release()
        idle()
    }

    private fun browser(listener: MediaBrowser.Listener = object : MediaBrowser.Listener {}): MediaBrowser {
        val future = MediaBrowser.Builder(context, session.token).setListener(listener).buildAsync()
        idle()
        return future.get().also { browsers += it }
    }

    private fun MediaBrowser.children(id: String): LibraryResult<ImmutableList<MediaItem>> {
        val result = getChildren(id, 0, Int.MAX_VALUE, null)
        idle()
        return result.get()
    }

    private fun titles(result: LibraryResult<ImmutableList<MediaItem>>) = result.value!!.map { it.mediaMetadata.title.toString() }

    @Test
    fun `a media browser connects and gets the library root`() {
        val result = browser().getLibraryRoot(null)
        idle()

        assertThat(result.get().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        val root = result.get().value!!
        assertThat(root.mediaId).isEqualTo("root")
        assertThat(root.mediaMetadata.isBrowsable).isTrue()
        assertThat(root.mediaMetadata.isPlayable).isFalse()
    }

    @Test
    fun `the root lists four named tabs, albums as a grid and songs as a list`() {
        val tabs = browser().children("root")

        assertThat(tabs.value!!.map { it.mediaId }).containsExactly("tab/playlists", "tab/albums", "tab/artists", "tab/songs")
            .inOrder()
        assertThat(titles(tabs)).containsExactly("Playlists", "Albums", "Artists", "Songs").inOrder()
        assertThat(tabs.value!!.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false }).isTrue()
        val albums = tabs.value!![1].mediaMetadata.extras!!
        assertThat(albums.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        val songs = tabs.value!![3].mediaMetadata.extras!!
        assertThat(songs.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    @Test
    fun `an album lists its songs as playable items with their details`() {
        val songs = browser().children("album/10")

        assertThat(songs.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(songs.value!!.map { it.mediaId }).containsExactly("song/1/0@album/10", "song/2/1@album/10").inOrder()
        val first = songs.value!!.first().mediaMetadata
        assertThat(first.title.toString()).isEqualTo("Intro")
        assertThat(first.artist.toString()).isEqualTo("Artist")
        assertThat(first.albumTitle.toString()).isEqualTo("First")
        assertThat(first.durationMs).isEqualTo(200_000L)
        assertThat(first.isPlayable).isTrue()
        assertThat(first.isBrowsable).isFalse()
        assertThat(first.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    }

    @Test
    fun `pages split a long list`() {
        val page = browser().getChildren("tab/songs", 1, 2, null)
        idle()

        assertThat(page.get().value!!.map { it.mediaMetadata.title.toString() }).containsExactly("Second Song")
    }

    @Test
    fun `favourites show under localised group titles`() {
        runBlocking { marks.setFavourite(ItemRef.song(library.tracks.value[2]), true) }
        runBlocking { marks.setFavourite(ItemRef.album(10), true) }

        val nodes = browser().children("favourites")

        assertThat(titles(nodes)).containsExactly("Other", "First").inOrder()
        assertThat(nodes.value!!.map { it.mediaMetadata.extras?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE) })
            .containsExactly("Songs", "Albums").inOrder()
    }

    @Test
    fun `artwork reaches the car through Sonora's artwork provider`() {
        val album = browser().children("album/20").value!!.single().mediaMetadata

        assertThat(album.artworkUri.toString()).isEqualTo("content://${context.packageName}.artwork/c_0a1b.jpg")
    }

    @Test
    fun `items are found by id`() {
        val result = browser().getItem("album/20")
        idle()

        assertThat(result.get().value!!.mediaMetadata.title.toString()).isEqualTo("Late")
        assertThat(result.get().value!!.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_ALBUM)
    }

    @Test
    fun `an id that names nothing is a bad value, never a crash`() {
        val browser = browser()

        assertThat(browser.children("album/999").resultCode).isEqualTo(LibraryResult.RESULT_ERROR_BAD_VALUE)
        assertThat(browser.children("12:34").resultCode).isEqualTo(LibraryResult.RESULT_ERROR_BAD_VALUE)
        val item = browser.getItem("song/99/0@album/10")
        idle()
        assertThat(item.get().resultCode).isEqualTo(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    @Test
    fun `a subscribed list is told when the library changes`() {
        val changed = mutableListOf<String>()
        val browser = browser(object : MediaBrowser.Listener {
            override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: LibraryParams?) {
                changed += parentId
            }
        })
        browser.subscribe("tab/songs", null)
        idle()
        changed.clear()

        library.tracks.value = library.tracks.value.take(2)
        idle()

        assertThat(changed).contains("tab/songs")
    }

    private fun trackIds() = List(player.mediaItemCount) { QueueMediaId.parse(player.getMediaItemAt(it).mediaId).trackId }

    /** What Android Auto does when a song is tapped: one item naming it, then prepare and play. */
    private fun MediaBrowser.tap(mediaId: String) {
        setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        prepare()
        play()
        idle()
    }

    @Test
    fun `a tapped album song queues the whole album from it, with full track details`() {
        browser().tap("song/2/1@album/10")

        assertThat(trackIds()).containsExactly(1L, 2L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.playWhenReady).isTrue()
        val state = playback.state.value
        // The app's queue owner adopted the car's queue as is: full tracks, not tracks rebuilt from media items.
        assertThat(state.queue.map { it.track }).isEqualTo(library.tracks.value.take(2))
        assertThat(state.currentTrack?.sizeBytes).isEqualTo(1234L)
    }

    @Test
    fun `playing from the car starts unshuffled, like a tap in the app`() {
        playback.playQueue(library.tracks.value, startIndex = 0, shuffle = true)
        idle()
        assertThat(player.shuffleModeEnabled).isTrue()

        browser().tap("song/3/2@tab/songs")

        assertThat(player.shuffleModeEnabled).isFalse()
        assertThat(playback.state.value.shuffleEnabled).isFalse()
        assertThat(trackIds()).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test
    fun `the app's own queue still plays as before after the car played`() {
        browser().tap("song/1/0@album/10")

        playback.playQueue(listOf(library.tracks.value[2]), startIndex = 0)
        idle()

        assertThat(trackIds()).containsExactly(3L)
        assertThat(player.currentMediaItem!!.localConfiguration!!.uri.toString()).isEqualTo("content://io.sonora.test/doc/3")
        assertThat(playback.state.value.queue.map { it.track.id }).containsExactly(3L)
    }

    @Test
    fun `a car id is never read as a queue entry`() {
        assertThat(QueueMediaId.parse("song/2/1@album/10").trackId).isNull()
        assertThat(QueueMediaId.parse("album/10").trackId).isNull()
    }

    @Test
    fun `the root tells the car that Sonora can search`() {
        val result = browser().getLibraryRoot(null)
        idle()

        assertThat(result.get().params!!.extras.getBoolean(SEARCH_SUPPORTED)).isTrue()
    }

    @Test
    fun `a search announces its result count, then lists the matches`() {
        val counts = mutableListOf<Pair<String, Int>>()
        val browser = browser(object : MediaBrowser.Listener {
            override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
                counts += query to itemCount
            }
        })

        browser.search("intro", null)
        idle()
        val result = browser.getSearchResult("intro", 0, 50, null)
        idle()

        assertThat(counts).containsExactly("intro" to 1)
        assertThat(result.get().value!!.map { it.mediaId }).containsExactly("song/1/0@search/intro")
    }

    /** What Android Auto sends for "play [query] on Sonora": one item holding the query and the assistant's extras. */
    private fun MediaBrowser.say(query: String, extras: Bundle = Bundle.EMPTY) {
        val request = MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()
        setMediaItem(MediaItem.Builder().setRequestMetadata(request).build())
        prepare()
        play()
        idle()
    }

    @Test
    fun `play an album by voice`() {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_ALBUM, "First")
        }

        browser().say("first", extras)

        assertThat(trackIds()).containsExactly(1L, 2L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
        assertThat(player.playWhenReady).isTrue()
        assertThat(playback.state.value.currentTrack).isEqualTo(library.tracks.value[0])
    }

    @Test
    fun `an empty voice request shuffles the whole library when nothing is queued`() {
        browser().say("")

        assertThat(trackIds()).containsExactly(1L, 2L, 3L)
        assertThat(player.shuffleModeEnabled).isTrue()
        assertThat(playback.state.value.shuffleEnabled).isTrue()
        assertThat(player.playWhenReady).isTrue()
    }

    @Test
    fun `an empty voice request resumes what is queued`() {
        playback.playQueue(library.tracks.value.reversed(), startIndex = 1)
        idle()
        playback.pause()
        idle()

        browser().say("")

        assertThat(trackIds()).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.playWhenReady).isTrue()
    }

    @Test
    fun `a voice request matching nothing says so and leaves the queue alone`() {
        playback.playQueue(listOf(library.tracks.value[2]), startIndex = 0)
        idle()
        val errors = mutableListOf<SessionError>()
        val browser = browser(object : MediaBrowser.Listener {
            override fun onError(controller: androidx.media3.session.MediaController, sessionError: SessionError) {
                errors += sessionError
            }
        })

        browser.say("zzz top")

        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(errors.single().message).contains("zzz top")
        assertThat(trackIds()).containsExactly(3L)
    }

    private fun errorsOf(): Pair<MediaBrowser, MutableList<SessionError>> {
        val errors = mutableListOf<SessionError>()
        val browser = browser(object : MediaBrowser.Listener {
            override fun onError(controller: androidx.media3.session.MediaController, sessionError: SessionError) {
                errors += sessionError
            }
        })
        return browser to errors
    }

    @Test
    fun `the car's library work runs off the session's thread`() {
        val (browser, errors) = errorsOf()

        browser.children("root")
        browser.children("tab/songs")
        browser.tap("song/1/0@album/10")

        assertThat(errors).isEmpty()
        assertThat(work.dispatches).isAtLeast(3)
        assertThat(readChecks).isNotEmpty()
        assertThat(readChecks).doesNotContain(false)
    }

    @Test
    fun `a library with songs to play browses without errors`() {
        val (browser, errors) = errorsOf()

        browser.children("root")

        assertThat(errors).isEmpty()
    }

    @Test
    fun `an empty library still shows the tabs, and asks to add music on the phone`() {
        library.tracks.value = emptyList()
        val (browser, errors) = errorsOf()

        val tabs = browser.children("root")

        assertThat(tabs.value).hasSize(4)
        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_SESSION_SETUP_REQUIRED)
        assertThat(errors.single().message).contains("empty")
        assertThat(errors.single().extras.getString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT))
            .isEqualTo("Open Sonora")
        assertThat(errors.single().extras.containsKey(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT)).isTrue()
    }

    @Test
    fun `lost folder access asks to open Sonora on the phone`() {
        unreadable += listOf(1L, 2L, 3L)
        val (browser, errors) = errorsOf()

        browser.children("root")

        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_SESSION_SETUP_REQUIRED)
        assertThat(errors.single().message).contains("music folders")
    }

    @Test
    fun `a song gone since browsing says so and leaves the queue alone`() {
        playback.playQueue(listOf(library.tracks.value[2]), startIndex = 0)
        idle()
        val (browser, errors) = errorsOf()

        browser.tap("song/99/0@album/10")

        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_BAD_VALUE)
        assertThat(trackIds()).containsExactly(3L)
    }

    /** The queue of song 3 only, paused. */
    private fun pausedOldQueue() {
        playback.playQueue(listOf(library.tracks.value[2]), startIndex = 0)
        idle()
        playback.pause()
        idle()
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `a tap that fails does not start the paused queue`() {
        pausedOldQueue()
        val (browser, errors) = errorsOf()

        browser.tap("song/99/0@album/10")

        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_BAD_VALUE)
        assertThat(trackIds()).containsExactly(3L)
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `a voice request matching nothing does not start the paused queue`() {
        pausedOldQueue()
        val (browser, errors) = errorsOf()

        browser.say("zzz top")

        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(trackIds()).containsExactly(3L)
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `after a failed request the same car still plays what it asks for next, and its play button works`() {
        pausedOldQueue()
        val (browser, _) = errorsOf()
        browser.tap("song/99/0@album/10")

        browser.tap("song/1/0@album/10")
        assertThat(trackIds()).containsExactly(1L, 2L).inOrder()
        assertThat(player.playWhenReady).isTrue()

        browser.pause()
        idle()
        browser.play()
        idle()
        assertThat(player.playWhenReady).isTrue()
    }

    @Test
    fun `songs that cannot be read are skipped, and a tapped one that cannot be read says so`() {
        unreadable += 2L
        val (browser, errors) = errorsOf()

        browser.tap("song/1/0@album/10")
        assertThat(trackIds()).containsExactly(1L)
        assertThat(errors).isEmpty()

        browser.tap("song/2/1@album/10")
        assertThat(errors.map { it.code }).containsExactly(SessionError.ERROR_SESSION_SETUP_REQUIRED)
        assertThat(trackIds()).containsExactly(1L)
    }

    private companion object {
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }

}
