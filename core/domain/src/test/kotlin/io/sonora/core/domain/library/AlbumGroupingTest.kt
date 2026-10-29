package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumGroupingTest {

    private fun track(
        name: String,
        folder: String = "Music/Album",
        title: String? = name.substringBeforeLast('.'),
        artist: String? = "Band",
        album: String? = "Record",
        albumArtist: String? = null,
        disc: Int? = null,
        number: Int? = null,
        compilation: Boolean = false,
        embedded: String? = null,
        folderImage: String? = null,
    ) = ScannedTrack(
        documentUri = "content://tree/$folder/$name",
        folderPath = folder,
        fileName = name,
        tags = TrackTags(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            discNumber = disc, trackNumber = number, isCompilation = compilation,
        ),
        embeddedArtworkUri = embedded,
        folderImageUri = folderImage,
    )

    private fun Map<String, ResolvedTrack>.albumIds() = values.map { it.albumId }.toSet()

    @Test
    fun `album title and artist spellings that differ only in case, spacing or unicode form merge`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.flac", album = "Café  Blue", artist = "Band"),
                track("2.flac", album = "café blue ", artist = " BAND"),
                track("3.flac", album = "Café Blue", artist = "Band"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        val first = result.values.first()
        assertThat(first.album).isEqualTo("Café Blue")
        assertThat(first.artist).isEqualTo("Band")
        assertThat(result.values.map { it.artistId }.toSet()).hasSize(1)
    }

    @Test
    fun `discs of one album merge into one album`() {
        val result = AlbumGrouping.resolve(
            listOf(track("1.flac", disc = 1, number = 1), track("2.flac", disc = 2, number = 1)),
        )
        assertThat(result.albumIds()).hasSize(1)
    }

    @Test
    fun `album artist is preferred over track artist for the album key`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.flac", artist = "Singer", albumArtist = "Band"),
                track("2.flac", artist = "Singer feat. Guest", albumArtist = "Band"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        assertThat(result.values.map { it.albumArtist }.toSet()).containsExactly("Band")
        assertThat(result.values.first().isCompilation).isFalse()
        assertThat(result.values.map { it.artistId }.toSet()).hasSize(2)
    }

    @Test
    fun `several artists sharing an album title in one folder without album artist become Various Artists`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.mp3", folder = "Music/Hits", artist = "A", album = "Hits 2020"),
                track("2.mp3", folder = "Music/Hits", artist = "B", album = "Hits 2020"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        val album = result.values.first()
        assertThat(album.albumArtist).isEqualTo(AlbumGrouping.VARIOUS_ARTISTS)
        assertThat(album.isCompilation).isTrue()
        assertThat(result.values.map { it.artist }).containsExactly("A", "B")
    }

    @Test
    fun `compilation spread over disc folders stays one Various Artists album`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.mp3", folder = "Music/Hits/CD1", artist = "A", album = "Hits"),
                track("2.mp3", folder = "Music/Hits/CD1", artist = "B", album = "Hits"),
                track("1.mp3", folder = "Music/Hits/CD2", artist = "C", album = "Hits"),
                track("2.mp3", folder = "Music/Hits/CD2", artist = "D", album = "Hits"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        assertThat(result.values.map { it.albumArtist }.toSet()).containsExactly(AlbumGrouping.VARIOUS_ARTISTS)
    }

    @Test
    fun `different Various Artists compilations sharing a title stay apart`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.mp3", folder = "Music/Hits A", artist = "A", album = "Greatest Hits"),
                track("2.mp3", folder = "Music/Hits A", artist = "B", album = "Greatest Hits"),
                track("1.mp3", folder = "Music/Hits B", artist = "C", album = "Greatest Hits", compilation = true),
                track("1.mp3", folder = "Music/Hits C", artist = "D", album = "Greatest Hits", albumArtist = "Various Artists"),
                track("2.mp3", folder = "Music/Hits C", artist = "E", album = "Greatest Hits", albumArtist = "various artists"),
            ),
        )
        assertThat(result.albumIds()).hasSize(3)
        assertThat(result.values.map { it.albumArtist }.toSet()).containsExactly(AlbumGrouping.VARIOUS_ARTISTS)
    }

    @Test
    fun `compilation with one artist per disc folder is one Various Artists album`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.mp3", folder = "Music/Hits/Disc 1", artist = "A", album = "Hits"),
                track("1.mp3", folder = "Music/Hits/Disc 2", artist = "B", album = "Hits"),
                track("1.mp3", folder = "Music/Hits/cd_3", artist = "C", album = "Hits"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        assertThat(result.values.map { it.albumArtist }.toSet()).containsExactly(AlbumGrouping.VARIOUS_ARTISTS)
    }

    @Test
    fun `explicit compilation flag without album artist means Various Artists, even with one artist`() {
        val result = AlbumGrouping.resolve(listOf(track("1.m4a", compilation = true)))
        val album = result.values.single()
        assertThat(album.albumArtist).isEqualTo(AlbumGrouping.VARIOUS_ARTISTS)
        assertThat(album.isCompilation).isTrue()
    }

    @Test
    fun `explicit album artist wins over the compilation flag`() {
        val result = AlbumGrouping.resolve(listOf(track("1.m4a", albumArtist = "DJ Mix", compilation = true)))
        val album = result.values.single()
        assertThat(album.albumArtist).isEqualTo("DJ Mix")
        assertThat(album.isCompilation).isTrue()
    }

    @Test
    fun `same album title by different artists in different folders stays separate`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.flac", folder = "Music/A", artist = "A", album = "Greatest Hits"),
                track("1.flac", folder = "Music/B", artist = "B", album = "Greatest Hits"),
            ),
        )
        assertThat(result.albumIds()).hasSize(2)
        assertThat(result.values.none { it.isCompilation }).isTrue()
    }

    @Test
    fun `one folder with loose tracks of several albums yields several albums`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.mp3", folder = "Music/Loose", artist = "A", album = "First"),
                track("2.mp3", folder = "Music/Loose", artist = "A", album = "Second"),
                track("3.mp3", folder = "Music/Loose", artist = "B", album = "Third"),
            ),
        )
        assertThat(result.albumIds()).hasSize(3)
        assertThat(result.values.none { it.isCompilation }).isTrue()
    }

    @Test
    fun `one album split over several folders stays one album`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.flac", folder = "Music/Record/CD1", disc = 1),
                track("1.flac", folder = "Music/Record/CD2", disc = 2),
                track("bonus.flac", folder = "Downloads"),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
    }

    @Test
    fun `untagged files fall back to file name, folder album and unknown artist`() {
        val untagged = ScannedTrack(
            documentUri = "content://tree/Music/Live Set/01 Intro.aiff",
            folderPath = "Music/Live Set",
            fileName = "01 Intro.aiff",
            tags = TrackTags.UNTAGGED,
        )
        val resolved = AlbumGrouping.resolve(listOf(untagged)).getValue(untagged.documentUri)
        assertThat(resolved.title).isEqualTo("01 Intro")
        assertThat(resolved.album).isEqualTo("Live Set")
        assertThat(resolved.artist).isEqualTo(AlbumGrouping.UNKNOWN_ARTIST)
        assertThat(resolved.albumArtist).isEqualTo(AlbumGrouping.UNKNOWN_ARTIST)
        assertThat(resolved.isCompilation).isFalse()
    }

    @Test
    fun `blank and padded tags count as missing`() {
        val resolved = AlbumGrouping.resolve(
            listOf(track("song.wav", folder = "Music/Demo", title = " ", artist = "\u0000", album = "")),
        ).values.single()
        assertThat(resolved.title).isEqualTo("song")
        assertThat(resolved.artist).isEqualTo(AlbumGrouping.UNKNOWN_ARTIST)
        assertThat(resolved.album).isEqualTo("Demo")
    }

    @Test
    fun `untagged files in different folders with the same name stay separate albums`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("a.wav", folder = "Music/X/Disc 1", title = null, artist = null, album = null),
                track("b.wav", folder = "Music/Y/Disc 1", title = null, artist = null, album = null),
            ),
        )
        assertThat(result.albumIds()).hasSize(2)
        assertThat(result.values.map { it.album }.toSet()).containsExactly("Disc 1")
    }

    @Test
    fun `files without album tag by several artists in one folder become one Various Artists folder album`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("a.wav", folder = "Music/Mix", artist = "A", album = null),
                track("b.wav", folder = "Music/Mix", artist = "B", album = null),
            ),
        )
        assertThat(result.albumIds()).hasSize(1)
        val album = result.values.first()
        assertThat(album.album).isEqualTo("Mix")
        assertThat(album.albumArtist).isEqualTo(AlbumGrouping.VARIOUS_ARTISTS)
        assertThat(album.isCompilation).isTrue()
    }

    @Test
    fun `album artwork prefers embedded art from the first track, then the folder image`() {
        val embedded = AlbumGrouping.resolve(
            listOf(
                track("2.flac", number = 2, embedded = "file:///art/b.jpg", folderImage = "content://cover"),
                track("1.flac", number = 1, embedded = "file:///art/a.jpg"),
                track("3.flac", number = 3),
            ),
        )
        assertThat(embedded.values.map { it.artworkUri }.toSet()).containsExactly("file:///art/a.jpg")

        val folderOnly = AlbumGrouping.resolve(
            listOf(track("1.flac", number = 1), track("2.flac", number = 2, folderImage = "content://cover")),
        )
        assertThat(folderOnly.values.map { it.artworkUri }.toSet()).containsExactly("content://cover")

        val none = AlbumGrouping.resolve(listOf(track("1.flac")))
        assertThat(none.values.single().artworkUri).isNull()
    }

    @Test
    fun `ids are stable across scans, input order and unrelated library changes`() {
        val a = track("1.flac", album = "Record", artist = "Band")
        val b = track("2.flac", album = "record ", artist = "band")
        val first = AlbumGrouping.resolve(listOf(a, b))
        val second = AlbumGrouping.resolve(listOf(track("x.mp3", folder = "Other", artist = "Z", album = "Q"), b, a))
        assertThat(second.getValue(a.documentUri).albumId).isEqualTo(first.getValue(a.documentUri).albumId)
        assertThat(second.getValue(a.documentUri).artistId).isEqualTo(first.getValue(a.documentUri).artistId)
        assertThat(first.getValue(a.documentUri).albumId).isEqualTo(
            AlbumGrouping.stableId("album", "band\u0001record"),
        )
    }

    @Test
    fun `album artist and track artist with the same name share one artist id`() {
        val result = AlbumGrouping.resolve(listOf(track("1.flac", artist = "Band", albumArtist = "band")))
        val resolved = result.values.single()
        assertThat(resolved.albumArtistId).isEqualTo(resolved.artistId)
    }

    @Test
    fun `stable ids are positive and namespaced`() {
        val album = AlbumGrouping.stableId("album", "x")
        val artist = AlbumGrouping.stableId("artist", "x")
        assertThat(album).isGreaterThan(0L)
        assertThat(artist).isGreaterThan(0L)
        assertThat(album).isNotEqualTo(artist)
        assertThat(AlbumGrouping.stableId("album", "x")).isEqualTo(album)
    }

    @Test
    fun `normalize folds case, whitespace and unicode composition`() {
        assertThat(AlbumGrouping.normalize("  Ｃafé\tDEL   Mar ")).isEqualTo("café del mar")
    }

    @Test
    fun `most frequent spelling is shown for a merged album`() {
        val result = AlbumGrouping.resolve(
            listOf(
                track("1.flac", album = "THE RECORD"),
                track("2.flac", album = "The Record"),
                track("3.flac", album = "The Record"),
            ),
        )
        assertThat(result.values.map { it.album }.toSet()).containsExactly("The Record")
    }
}
