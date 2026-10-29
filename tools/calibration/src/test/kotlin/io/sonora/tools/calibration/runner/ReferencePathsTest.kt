package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.PdbTrack
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths
import java.text.Normalizer

class ReferencePathsTest {

    private val root = Paths.get("/media/usb")

    @Test
    fun `a device path resolves under the export root`() {
        val file = ReferencePaths.resolve(root, "/Contents/Artist/Album/01 Track.mp3")
        assertThat(file).isEqualTo(Paths.get("/media/usb/Contents/Artist/Album/01 Track.mp3"))
    }

    @Test
    fun `a selection path without the leading slash resolves the same way`() {
        assertThat(ReferencePaths.resolve(root, "Contents/a.wav")).isEqualTo(ReferencePaths.resolve(root, "/Contents/a.wav"))
    }

    @Test
    fun `a path that climbs out of the root is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReferencePaths.resolve(root, "/Contents/../../etc/passwd") }
    }

    @Test
    fun `the analyze path gives the DAT EXT and 2EX siblings`() {
        val set = ReferencePaths.anlzSet(root, "/PIONEER/USBANLZ/P065/0001A961/ANLZ0000.DAT")
        assertThat(set.dat).isEqualTo(Paths.get("/media/usb/PIONEER/USBANLZ/P065/0001A961/ANLZ0000.DAT"))
        assertThat(set.ext).isEqualTo(Paths.get("/media/usb/PIONEER/USBANLZ/P065/0001A961/ANLZ0000.EXT"))
        assertThat(set.twoEx).isEqualTo(Paths.get("/media/usb/PIONEER/USBANLZ/P065/0001A961/ANLZ0000.2EX"))
    }

    @Test
    fun `an analyze path that is not a DAT file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReferencePaths.anlzSet(root, "/PIONEER/USBANLZ/P0/ANLZ0000.EXT") }
    }

    @Test
    fun `keys ignore the leading slash and the Unicode normal form`() {
        val composed = "/Contents/Esaïa/Café.flac"
        val decomposed = Normalizer.normalize("Contents/Esaïa/Café.flac", Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo(composed.substring(1))
        assertThat(ReferencePaths.key(decomposed)).isEqualTo(ReferencePaths.key(composed))
    }

    @Test
    fun `formats are lower-case extensions with aif and aiff together`() {
        assertThat(ReferencePaths.format("Contents/a/B.MP3")).isEqualTo("mp3")
        assertThat(ReferencePaths.format("Contents/a/b.aif")).isEqualTo("aiff")
        assertThat(ReferencePaths.format("Contents/a/b.aiff")).isEqualTo("aiff")
        assertThat(ReferencePaths.format("Contents/a/b.flac")).isEqualTo("flac")
        assertThat(ReferencePaths.format("Contents/a/b.m4a")).isEqualTo("m4a")
        assertThat(ReferencePaths.format("Contents/a/noextension")).isEqualTo("unknown")
    }

    @Test
    fun `selection entries are matched to export rows by file path`() {
        val rows = listOf(
            track(1, "/Contents/A/one.mp3"),
            track(2, "/Contents/B/Café.wav"),
            track(3, "/Contents/C/three.flac"),
        )
        val match = ReferencePaths.match(
            selection = listOf("Contents/C/three.flac", Normalizer.normalize("Contents/B/Café.wav", Normalizer.Form.NFD), "Contents/missing.aif"),
            tracks = rows,
        )
        assertThat(match.matched.map { it.second.id }).containsExactly(3, 2).inOrder()
        assertThat(match.matched.map { it.first }).containsExactly(
            "Contents/C/three.flac",
            Normalizer.normalize("Contents/B/Café.wav", Normalizer.Form.NFD),
        ).inOrder()
        assertThat(match.unmatched).containsExactly("Contents/missing.aif")
    }

    @Test
    fun `a path exported twice matches the row with the lowest id`() {
        val match = ReferencePaths.match(listOf("Contents/a.mp3"), listOf(track(9, "/Contents/a.mp3"), track(4, "/Contents/a.mp3")))
        assertThat(match.matched.single().second.id).isEqualTo(4)
    }

    @Test
    fun `blank selection lines are ignored`() {
        val match = ReferencePaths.match(listOf("", "   ", "Contents/a.mp3"), listOf(track(1, "/Contents/a.mp3")))
        assertThat(match.matched).hasSize(1)
        assertThat(match.unmatched).isEmpty()
    }

    private fun track(id: Int, filePath: String) = PdbTrack(
        id = id, title = "t$id", artistId = 0, albumId = 0, genreId = 0, labelId = 0, keyId = 0, artworkId = 0,
        filePath = filePath, fileName = filePath.substringAfterLast('/'), analyzePath = "/PIONEER/USBANLZ/P000/$id/ANLZ0000.DAT",
        analyzeDate = "", dateAdded = "", comment = "", tempoHundredths = 12_000, durationSeconds = 300, sampleRate = 44_100,
        sampleDepth = 16, bitrate = 1411, fileSize = 1, fileType = 0, trackNumber = 0, year = 0,
    )
}
