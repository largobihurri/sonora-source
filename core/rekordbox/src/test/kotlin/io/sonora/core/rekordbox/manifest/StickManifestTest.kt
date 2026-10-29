package io.sonora.core.rekordbox.manifest

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.pdb.DeviceFileType
import io.sonora.core.rekordbox.pdb.PdbWriter
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

class StickManifestTest {

    private val day = LocalDate.of(2026, 10, 3)

    private fun track(id: Int, title: String = "Track $id") = ManifestTrack(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        genre = "Techno",
        trackNumber = 2,
        year = 2024,
        filePath = "/Contents/Artist/Album/$title.aiff",
        analyzePath = "/PIONEER/USBANLZ/P001/0000000$id/ANLZ0000.DAT",
        fileSize = 1_000_000,
        contentSha256 = "ab".repeat(32),
        sampleRate = 44_100,
        bitDepth = 16,
        channelCount = 2,
        durationSeconds = 200,
        bpm = 128.0,
        key = MusicalKey.A_MINOR,
        dateAdded = day,
        analyzerVersion = "sonora-analysis/2",
    )

    private val manifest = StickManifest(
        tracks = listOf(
            track(1, "Ünïcödé \"quoted\" \\ back\nline 🎵").copy(comment = "Visit\nthe \"label\"", artworkId = 1),
            track(2).copy(artist = null, album = null, genre = null, trackNumber = null, year = null, key = null),
        ),
        playlists = listOf(ManifestPlaylist("Sonora 2026-10-03", listOf(2, 1), created = day), ManifestPlaylist("Old", listOf(1))),
        artwork = listOf(ManifestArtwork(1, "cd".repeat(32))),
    )

    /** A track as a Sonora before C24 wrote it: no comment, no artwork. */
    private val oldTrackJson = """
        {"id":1,"title":"Old","artist":"A","album":null,"genre":"Dub","trackNumber":null,"year":null,
         "filePath":"/Contents/A/UnknownAlbum/Old.aiff","analyzePath":"/PIONEER/USBANLZ/P001/00000001/ANLZ0000.DAT",
         "fileSize":10,"sha256":"${"ab".repeat(32)}","sampleRate":44100,"bitDepth":16,"channels":2,
         "durationSeconds":200,"bpm":103.0,"key":"8A","dateAdded":"2026-10-04","fileType":"AIFF","analyzer":"x"}
    """.trimIndent()

    @Test
    fun `a manifest survives encoding and decoding`() {
        assertThat(ManifestJson.decode(ManifestJson.encode(manifest))).isEqualTo(manifest)
    }

    @Test
    fun `comments and artwork survive encoding and decoding`() {
        val decoded = ManifestJson.decode(ManifestJson.encode(manifest))

        assertThat(decoded.tracks[0].comment).isEqualTo("Visit\nthe \"label\"")
        assertThat(decoded.tracks[0].artworkId).isEqualTo(1)
        assertThat(decoded.tracks[0].extrasRead).isTrue()
        assertThat(decoded.tracks[1].comment).isNull()
        assertThat(decoded.tracks[1].artworkId).isNull()
        assertThat(decoded.tracks[1].extrasRead).isTrue()
        assertThat(decoded.artwork).containsExactly(ManifestArtwork(1, "cd".repeat(32)))
        val json = ManifestJson.encode(manifest).toString(Charsets.UTF_8)
        assertThat(json).contains("\"comment\":null,\"artworkId\":null}")
        assertThat(json).contains("\"artwork\":[\n{\"id\":1,\"sha256\":\"${"cd".repeat(32)}\"}]")
    }

    @Test
    fun `a manifest from before comments and artwork reads as tracks whose extras are still to be read`() {
        val json = "{\"format\":\"sonora-stick-library\",\"version\":1,\"tracks\":[$oldTrackJson],\"playlists\":[]}"

        val decoded = ManifestJson.decode(json.toByteArray())

        val track = decoded.tracks.single()
        assertThat(track.comment).isNull()
        assertThat(track.artworkId).isNull()
        assertThat(track.extrasRead).isFalse()
        assertThat(track.genre).isEqualTo("Dub")
        assertThat(decoded.artwork).isEmpty()
        // Written again, the track says its extras are still unread until an export reads them.
        assertThat(ManifestJson.decode(ManifestJson.encode(decoded)).tracks.single().extrasRead).isFalse()
    }

    @Test
    fun `artwork with a repeated or non-positive id is refused`() {
        val twice = ManifestJson.encode(manifest.copy(artwork = listOf(ManifestArtwork(1, "a"), ManifestArtwork(1, "b"))))
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(twice) }
        val zero = ManifestJson.encode(manifest.copy(artwork = listOf(ManifestArtwork(0, "a"))))
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(zero) }
    }

    @Test
    fun `the next artwork id follows the highest one`() {
        assertThat(StickManifest().nextArtworkId).isEqualTo(1)
        assertThat(StickManifest(artwork = listOf(ManifestArtwork(4, "a"), ManifestArtwork(2, "b"))).nextArtworkId).isEqualTo(5)
    }

    @Test
    fun `the device library carries comments and known artwork ids`() {
        val unknownArt = track(3).copy(artworkId = 7, comment = "x".repeat(400))

        val library = manifest.copy(tracks = manifest.tracks + unknownArt).toDeviceLibrary(day)

        assertThat(library.tracks[0].comment).isEqualTo("Visit the \"label\"")
        assertThat(library.tracks[0].artworkId).isEqualTo(1)
        assertThat(library.tracks[1].artworkId).isNull()
        assertThat(library.tracks[2].artworkId).isNull() // no such picture on the stick
        assertThat(library.tracks[2].comment!!.length).isAtMost(256)
        PdbWriter.write(library)
    }

    @Test
    fun `the JSON names its format, version and keys by their alphanumeric names`() {
        val json = ManifestJson.encode(manifest).toString(Charsets.UTF_8)

        assertThat(json).startsWith("{\"format\":\"sonora-stick-library\",\"version\":1,")
        assertThat(json).contains("\"key\":\"8A\"")
        assertThat(json).contains("\"sha256\":\"${"ab".repeat(32)}\"")
    }

    @Test
    fun `unknown members are ignored`() {
        val json = """
            {"format":"sonora-stick-library","version":1,"future":{"a":[1,2.5e3,true,null]},
             "tracks":[],"playlists":[{"name":"P","trackIds":[],"extra":"x"}]}
        """.trimIndent()

        assertThat(ManifestJson.decode(json.toByteArray())).isEqualTo(StickManifest(playlists = listOf(ManifestPlaylist("P", emptyList()))))
    }

    @Test
    fun `other formats, newer versions and damaged JSON are refused`() {
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode("{\"format\":\"x\",\"version\":1}".toByteArray()) }
        val newer = assertThrows(ManifestFormatException.NewerVersion::class.java) {
            ManifestJson.decode("{\"format\":\"sonora-stick-library\",\"version\":2}".toByteArray())
        }
        assertThat(newer.version).isEqualTo(2)
        val bytes = ManifestJson.encode(manifest)
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(bytes.copyOf(bytes.size - 3)) }
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(byteArrayOf()) }
    }

    @Test
    fun `tracks with missing members or repeated ids are refused`() {
        val json = ManifestJson.encode(manifest).toString(Charsets.UTF_8)

        assertThrows(ManifestFormatException.Malformed::class.java) {
            ManifestJson.decode(json.replace("\"fileSize\":1000000,", "").toByteArray())
        }
        val twice = ManifestJson.encode(manifest.copy(tracks = listOf(track(1), track(1, "Other"))))
        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(twice) }
    }

    @Test
    fun `a file size that is not a whole 32-bit size is refused`() {
        val json = ManifestJson.encode(manifest.copy(tracks = listOf(track(1)))).toString(Charsets.UTF_8)

        for (size in listOf("1000000.5", "-1", "4294967296", "1e30")) {
            assertThrows(size, ManifestFormatException.Malformed::class.java) {
                ManifestJson.decode(json.replace("\"fileSize\":1000000,", "\"fileSize\":$size,").toByteArray())
            }
        }
        val largest = json.replace("\"fileSize\":1000000,", "\"fileSize\":4294967295,")
        assertThat(ManifestJson.decode(largest.toByteArray()).tracks.single().fileSize).isEqualTo(0xFFFF_FFFFL)
    }

    @Test
    fun `whole numbers are never truncated into something else`() {
        val header = "{\"format\":\"sonora-stick-library\",\"version\":"
        for (version in listOf("1.5", "4294967297", "-4294967295")) {
            assertThrows(version, ManifestFormatException.Malformed::class.java) {
                ManifestJson.decode("$header$version}".toByteArray())
            }
        }
        val json = ManifestJson.encode(manifest).toString(Charsets.UTF_8)
        for ((from, to) in listOf("[2,1]" to "[4294967298,1]", "[2,1]" to "[2.5,1]", "\"year\":2024" to "\"year\":2024.5")) {
            assertThrows(to, ManifestFormatException.Malformed::class.java) {
                ManifestJson.decode(json.replaceFirst(from, to).toByteArray())
            }
        }
    }

    @Test
    fun `deeply nested JSON is refused, not a stack overflow`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val json = "{\"format\":\"sonora-stick-library\",\"version\":1,\"future\":$deep,\"tracks\":[],\"playlists\":[]}"

        assertThrows(ManifestFormatException.Malformed::class.java) { ManifestJson.decode(json.toByteArray()) }
        val nested = "[".repeat(20) + "]".repeat(20)
        val fine = "{\"format\":\"sonora-stick-library\",\"version\":1,\"future\":$nested}"
        assertThat(ManifestJson.decode(fine.toByteArray())).isEqualTo(StickManifest())
    }

    @Test
    fun `the next track id follows the highest one`() {
        assertThat(StickManifest().nextTrackId).isEqualTo(1)
        assertThat(StickManifest(tracks = listOf(track(4), track(2))).nextTrackId).isEqualTo(5)
    }

    @Test
    fun `the device library carries every track and playlist`() {
        val library = manifest.toDeviceLibrary(day)

        assertThat(library.exportDate).isEqualTo(day)
        val first = library.tracks.first()
        assertThat(first.id).isEqualTo(1)
        assertThat(first.filePath).isEqualTo("/Contents/Artist/Album/${manifest.tracks[0].title}.aiff")
        assertThat(first.analyzePath).isEqualTo("/PIONEER/USBANLZ/P001/00000001/ANLZ0000.DAT")
        assertThat(first.key).isEqualTo(MusicalKey.A_MINOR)
        assertThat(first.bitrateKbps).isEqualTo(1411)
        assertThat(first.tempoBpm).isEqualTo(128.0)
        assertThat(first.fileType).isEqualTo(DeviceFileType.AIFF)
        assertThat(first.title).isEqualTo("Ünïcödé \"quoted\" \\ back line 🎵")
        assertThat(library.playlists.map { it.name to it.trackIds })
            .containsExactly("Sonora 2026-10-03" to listOf(2, 1), "Old" to listOf(1)).inOrder()
        PdbWriter.write(library)
    }

    @Test
    fun `bad tags and values are cleaned instead of failing the library`() {
        val bad = track(1).copy(
            title = "x".repeat(5000),
            artist = "é".repeat(5000),
            year = 70_000,
            trackNumber = -4,
            bpm = Double.POSITIVE_INFINITY,
            durationSeconds = 100_000,
        )
        val untitled = track(2).copy(title = " ", filePath = "/Contents/A/B/My Song.aiff")
        val library = StickManifest(
            tracks = listOf(bad, untitled),
            playlists = listOf(ManifestPlaylist("P", listOf(1, 99, 2))),
        ).toDeviceLibrary(day)

        val first = library.tracks[0]
        assertThat(first.title.length).isAtMost(256)
        assertThat(first.artist!!.length).isAtMost(256)
        assertThat(first.year).isNull()
        assertThat(first.trackNumber).isNull()
        assertThat(first.tempoBpm).isEqualTo(0.0)
        assertThat(first.durationSeconds).isEqualTo(0xFFFF)
        assertThat(library.tracks[1].title).isEqualTo("My Song")
        assertThat(library.playlists.single().trackIds).containsExactly(1, 2).inOrder()
        PdbWriter.write(library)
    }
}
