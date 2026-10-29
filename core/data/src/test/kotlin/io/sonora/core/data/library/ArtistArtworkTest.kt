package io.sonora.core.data.library

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.HiddenItemEntity
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The covers each artist row stacks: one per album, most played first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArtistArtworkTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: RoomLibraryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
        library = RoomLibraryRepository(dao)
    }

    @After
    fun tearDown() = db.close()

    private fun song(name: String, artist: String, album: String, art: String?) = ScannedTrack(
        documentUri = "content://tree/music/document/$album/$name",
        folderPath = "Music/$album",
        fileName = name,
        tags = TrackTags(title = name.substringBeforeLast('.'), artist = artist, album = album),
        embeddedArtworkUri = art,
    )

    /** Stores [songs] as one scan does, with `dateAddedSec` = position. */
    private suspend fun index(vararg songs: ScannedTrack) {
        val resolution = AlbumGrouping.resolve(songs.toList())
        dao.upsertTracks(
            songs.mapIndexed { i, s ->
                TrackEntity.of(s, "content://tree/music", "audio/flac", FileIdentity(s.documentUri, 10, 20), i.toLong(), resolution.getValue(s.documentUri))
            },
        )
    }

    private suspend fun play(song: ScannedTrack, times: Int = 1) = repeat(times) { db.playCountDao().recordPlay(song.documentUri, 1) }

    private suspend fun covers(artist: String = "Band") = library.artists().first().single { it.name == artist }.artworkUris

    private val a1 = song("a1.flac", "Band", "A", "file:///a.jpg")
    private val a2 = song("a2.flac", "Band", "A", "file:///a.jpg")
    private val b1 = song("b1.flac", "Band", "B", "file:///b.jpg")
    private val c1 = song("c1.flac", "Band", "C", "file:///c.jpg")
    private val d1 = song("d1.flac", "Band", "D", "file:///d.jpg")
    private val e1 = song("e1.flac", "Band", "E", null)

    @Test
    fun `covers are one per album, most played first, then newest, at most three`() = runTest {
        index(a1, a2, b1, c1, d1, e1)
        play(a1, 2)
        play(a2, 3)
        play(b1, 4)

        // A: 5 plays, B: 4; C and D unplayed, D newer; E has no artwork.
        assertThat(covers()).containsExactly("file:///a.jpg", "file:///b.jpg", "file:///d.jpg").inOrder()
    }

    @Test
    fun `an artist without plays shows the newest albums first`() = runTest {
        index(a1, b1, c1)

        assertThat(covers()).containsExactly("file:///c.jpg", "file:///b.jpg", "file:///a.jpg").inOrder()
    }

    @Test
    fun `an artist without artwork has no covers`() = runTest {
        index(e1)

        assertThat(covers()).isEmpty()
    }

    @Test
    fun `hidden albums and songs give no cover and no plays`() = runTest {
        index(a1, a2, b1, c1)
        play(a1, 5)
        play(b1, 3)
        play(c1, 1)
        val albumB = dao.trackByUri(b1.documentUri)!!.albumId
        db.libraryMarksDao().insertHidden(HiddenItemEntity("ALBUM", albumB.toString(), "B", "Band", 1))
        db.libraryMarksDao().insertHidden(HiddenItemEntity("SONG", a1.documentUri, "a1", "Band", 1))

        // A's only visible song is unplayed, so C (1 play) leads; B is gone.
        assertThat(covers()).containsExactly("file:///c.jpg", "file:///a.jpg").inOrder()
    }

    @Test
    fun `a compilation counts the plays of all its songs for each artist on it`() = runTest {
        val mixBand = song("m1.flac", "Band", "Mix", "file:///mix.jpg")
        val mixGuest = song("m2.flac", "Guest", "Mix", "file:///mix.jpg")
        index(a1, mixBand, mixGuest)
        play(mixGuest, 2)
        play(a1, 1)

        assertThat(covers()).containsExactly("file:///mix.jpg", "file:///a.jpg").inOrder()
        assertThat(covers("Guest")).containsExactly("file:///mix.jpg")
    }

    @Test
    fun `artists re-emit with the new order when a song is played`() = runTest {
        index(a1, b1)
        runBlocking {
            val emissions = Channel<List<Artist>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { library.artists().collect { emissions.send(it) } }
            try {
                val before = withTimeout(5_000) { emissions.receive() }.single().artworkUris
                assertThat(before).containsExactly("file:///b.jpg", "file:///a.jpg").inOrder()

                play(a1)

                val after = withTimeout(5_000) {
                    var covers: List<String>
                    do {
                        covers = emissions.receive().single().artworkUris
                    } while (covers == before)
                    covers
                }
                assertThat(after).containsExactly("file:///a.jpg", "file:///b.jpg").inOrder()
            } finally {
                job.cancel()
            }
        }
    }
}
