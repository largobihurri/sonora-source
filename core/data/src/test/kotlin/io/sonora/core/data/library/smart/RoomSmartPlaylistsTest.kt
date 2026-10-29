package io.sonora.core.data.library.smart

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.HiddenItemEntity
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The smart playlists' queries in the library database (`LibraryDao`), through [RoomSmartPlaylists]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSmartPlaylistsTest {

    private lateinit var db: LibraryDatabase
    private lateinit var playlists: RoomSmartPlaylists

    private val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlists = RoomSmartPlaylists(db.libraryDao())
    }

    @After
    fun tearDown() = db.close()

    private class Song(val title: String, val album: String, val artist: String, val number: Int?, val addedSec: Long) {
        val scanned = ScannedTrack(
            documentUri = "content://tree/music/document/$album/$title",
            folderPath = "Music/$album",
            fileName = "$title.flac",
            tags = TrackTags(title = title, artist = artist, album = album, trackNumber = number),
        )
        val uri get() = scanned.documentUri
    }

    private fun song(title: String, addedSec: Long, album: String = "Record", artist: String = "Band", number: Int? = null) =
        Song(title, album, artist, number, addedSec)

    /** Indexes [songs] the way the scanner does after regrouping. */
    private suspend fun index(vararg songs: Song) {
        db.libraryDao().insertFolder(folder)
        val resolution = AlbumGrouping.resolve(songs.map { it.scanned })
        db.libraryDao().upsertTracks(
            songs.map {
                TrackEntity.of(it.scanned, folder.treeUri, "audio/flac", FileIdentity(it.uri, 10, 20), it.addedSec, resolution.getValue(it.uri))
            },
        )
    }

    private suspend fun play(song: Song, times: Int, lastAtMs: Long) = repeat(times) { db.playCountDao().recordPlay(song.uri, lastAtMs) }

    private suspend fun hide(kind: MarkKind, key: String) = db.libraryMarksDao().insertHidden(HiddenItemEntity(kind.name, key, "t", "s", 1))

    private suspend fun titlesOf(list: Flow<List<Track>>) = list.first().map { it.title }

    // Recently Added

    @Test
    fun `recently added keeps songs added at or after the cutoff, newest first`() = runTest {
        index(song("Old", addedSec = 999), song("Edge", addedSec = 1_000), song("New", addedSec = 2_000), song("Mid", addedSec = 1_500))

        assertThat(titlesOf(playlists.recentlyAdded(sinceSec = 1_000))).containsExactly("New", "Mid", "Edge").inOrder()
    }

    @Test
    fun `songs added at the same time keep their album order`() = runTest {
        index(
            song("Zulu", addedSec = 5, album = "B", number = 1),
            song("Bravo", addedSec = 5, album = "A", number = 2),
            song("Alpha", addedSec = 5, album = "A", number = 1),
            song("Older", addedSec = 4, album = "A", number = 3),
        )

        assertThat(titlesOf(playlists.recentlyAdded(sinceSec = 0))).containsExactly("Alpha", "Bravo", "Zulu", "Older").inOrder()
    }

    @Test
    fun `recently added leaves hidden songs, albums and artists out`() = runTest {
        val shown = song("Shown", addedSec = 10)
        val hiddenSong = song("Hidden song", addedSec = 10)
        val inHiddenAlbum = song("In hidden album", addedSec = 10, album = "Gone")
        val byHiddenArtist = song("By hidden artist", addedSec = 10, album = "Solo", artist = "Ghost")
        index(shown, hiddenSong, inHiddenAlbum, byHiddenArtist)
        val tracks = db.libraryDao().allTracks().associateBy { it.documentUri }
        hide(MarkKind.SONG, hiddenSong.uri)
        hide(MarkKind.ALBUM, tracks.getValue(inHiddenAlbum.uri).albumId.toString())
        hide(MarkKind.ARTIST, tracks.getValue(byHiddenArtist.uri).artistId.toString())

        assertThat(titlesOf(playlists.recentlyAdded(sinceSec = 0))).containsExactly("Shown")
    }

    // Most Played

    @Test
    fun `most played lists played songs by plays, ties by the latest play`() = runTest {
        val once = song("Once", addedSec = 1)
        val twiceEarlier = song("Twice earlier", addedSec = 1)
        val twiceLater = song("Twice later", addedSec = 1)
        val thrice = song("Thrice", addedSec = 1)
        val never = song("Never", addedSec = 1)
        index(once, twiceEarlier, twiceLater, thrice, never)
        play(once, 1, lastAtMs = 900)
        play(twiceEarlier, 2, lastAtMs = 100)
        play(twiceLater, 2, lastAtMs = 200)
        play(thrice, 3, lastAtMs = 50)

        assertThat(titlesOf(playlists.mostPlayed(limit = 50)))
            .containsExactly("Thrice", "Twice later", "Twice earlier", "Once").inOrder()
    }

    @Test
    fun `most played keeps only the top songs up to the limit`() = runTest {
        val songs = (1..4).map { song("Song $it", addedSec = 1) }
        index(*songs.toTypedArray())
        songs.forEachIndexed { i, song -> play(song, times = i + 1, lastAtMs = 10) }

        assertThat(titlesOf(playlists.mostPlayed(limit = 2))).containsExactly("Song 4", "Song 3").inOrder()
    }

    @Test
    fun `most played leaves hidden songs and plays of songs no longer in the library out`() = runTest {
        val shown = song("Shown", addedSec = 1)
        val hidden = song("Hidden", addedSec = 1)
        index(shown, hidden)
        play(shown, 1, lastAtMs = 1)
        play(hidden, 5, lastAtMs = 1)
        db.playCountDao().recordPlay("content://tree/music/document/removed", 1)
        hide(MarkKind.SONG, hidden.uri)

        assertThat(titlesOf(playlists.mostPlayed(limit = 50))).containsExactly("Shown")
    }

    // Never Played

    @Test
    fun `never played lists songs with no plays, newest added first`() = runTest {
        val played = song("Played", addedSec = 30)
        index(song("First", addedSec = 10), played, song("Second", addedSec = 20))
        play(played, 1, lastAtMs = 1)

        assertThat(titlesOf(playlists.neverPlayed())).containsExactly("Second", "First").inOrder()
    }

    @Test
    fun `never played leaves hidden songs out`() = runTest {
        val hidden = song("Hidden", addedSec = 1)
        index(song("Shown", addedSec = 1), hidden)
        hide(MarkKind.SONG, hidden.uri)

        assertThat(titlesOf(playlists.neverPlayed())).containsExactly("Shown")
    }
}
