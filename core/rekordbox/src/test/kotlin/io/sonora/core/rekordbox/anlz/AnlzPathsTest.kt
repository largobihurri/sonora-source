package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnlzPathsTest {

    @Test
    fun `paths follow the rekordbox USBANLZ layout`() {
        val paths = AnlzPaths.forAudioPath("/Contents/A/B/t.aiff")

        assertThat(paths.datPath).matches("PIONEER/USBANLZ/P[0-9A-F]{3}/[0-9A-F]{8}/ANLZ0000\\.DAT")
        assertThat(paths.extPath).isEqualTo(paths.datPath.removeSuffix(".DAT") + ".EXT")
        assertThat(paths.analyzePath).isEqualTo("/" + paths.datPath)
        assertThat(paths.ex2Path).isEqualTo(paths.datPath.removeSuffix(".DAT") + ".2EX")
    }

    @Test
    fun `folders are the ones rekordbox computes from the file path, which the players look in`() {
        // The two tracks of rekordbox's own demo export (test resources rekordcrate/demo_tracks).
        assertThat(AnlzPaths.forAudioPath("/Contents/Loopmasters/UnknownAlbum/Demo Track 1.mp3").datPath)
            .isEqualTo("PIONEER/USBANLZ/P016/0000875E/ANLZ0000.DAT")
        assertThat(AnlzPaths.forAudioPath("/Contents/Loopmasters/UnknownAlbum/Demo Track 2.mp3").datPath)
            .isEqualTo("PIONEER/USBANLZ/P053/0001D21F/ANLZ0000.DAT")
        // Checked on an XDJ-700: the player showed this track's waveforms only from this folder.
        val path = "/Contents/Ashanti Selah & Dan-I Locks/Vibes Up/" +
            "Ashanti Selah & Dan-I Locks - Vibes Up - 01 Vibes Up (Vocal Mix).aiff"
        assertThat(AnlzPaths.folderFor(path)).isEqualTo("PIONEER/USBANLZ/P065/0001B073")
    }

    @Test
    fun `the hash runs over UTF-16 code units, so names outside ASCII get their own folder`() {
        assertThat(AnlzPaths.folderFor("/Contents/Björk/Début/01 Human Behaviour.aiff"))
            .isNotEqualTo(AnlzPaths.folderFor("/Contents/Bjork/Debut/01 Human Behaviour.aiff"))
        assertThat(AnlzPaths.folderFor("/Contents/A/B/𝄞.aiff")).matches("PIONEER/USBANLZ/P[0-9A-F]{3}/[0-9A-F]{8}")
    }

    @Test
    fun `tracks sharing a folder get numbered files`() {
        val second = AnlzPaths.forAudioPath("/Contents/UnknownArtist/UnknownAlbum/10001.aiff", number = 1)

        assertThat(AnlzPaths.folderFor("/Contents/UnknownArtist/UnknownAlbum/9306.aiff"))
            .isEqualTo(AnlzPaths.folderFor("/Contents/UnknownArtist/UnknownAlbum/10001.aiff"))
        assertThat(second.datPath).endsWith("/ANLZ0001.DAT")
        assertThat(second.extPath).endsWith("/ANLZ0001.EXT")
        assertThat(second.analyzePath).isEqualTo("/" + second.datPath)
    }

    @Test
    fun `the same path always maps to the same folder`() {
        val a = AnlzPaths.forAudioPath("/Contents/Artist/Album/01.aiff")

        assertThat(AnlzPaths.forAudioPath("/Contents/Artist/Album/01.aiff")).isEqualTo(a)
        assertThat(AnlzPaths.forAudioPath("/Contents/Artist/Album/02.aiff").datPath).isNotEqualTo(a.datPath)
    }
}
