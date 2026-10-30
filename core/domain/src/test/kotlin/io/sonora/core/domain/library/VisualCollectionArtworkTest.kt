package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Album
import org.junit.Test

class VisualCollectionArtworkTest {

    private fun album(id: Long, artwork: String?) = Album(id, "Album $id", "Band", null, 10, artwork)

    private val withArt = album(1, "content://art/1")
    private val without = album(2, null)
    private val folderArt = album(3, "content://tree/music/cover.jpg")

    @Test
    fun `with the switch off every album is listed, artwork or not`() {
        val albums = listOf(withArt, without, folderArt)

        assertThat(VisualCollectionArtwork.albums(albums, hideWithoutArtwork = false)).isEqualTo(albums)
    }

    @Test
    fun `with the switch on only albums with artwork are listed, in their order`() {
        assertThat(VisualCollectionArtwork.albums(listOf(withArt, without, folderArt), hideWithoutArtwork = true))
            .containsExactly(withArt, folderArt).inOrder()
    }

    @Test
    fun `a blank artwork address counts as no artwork`() {
        assertThat(VisualCollectionArtwork.albums(listOf(album(4, " "), withArt), hideWithoutArtwork = true))
            .containsExactly(withArt)
    }

    @Test
    fun `with the switch on and no album with artwork, nothing is listed`() {
        assertThat(VisualCollectionArtwork.albums(listOf(without), hideWithoutArtwork = true)).isEmpty()
    }

    @Test
    fun `the centre album stays centred when it is still listed`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3, 4), centre = 2, after = listOf(1, 3, 4))).isEqualTo(1)
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 3, 4), centre = 1, after = listOf(1, 2, 3, 4))).isEqualTo(2)
    }

    @Test
    fun `a hidden centre album hands the centre to its nearest listed neighbour`() {
        // 3 goes; 4 is one step on, 1 two steps back.
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3, 4, 5), centre = 2, after = listOf(1, 4, 5))).isEqualTo(1)
        // 3 and 4 go; 2 is one step back, 5 two steps on.
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3, 4, 5), centre = 2, after = listOf(1, 2, 5))).isEqualTo(1)
    }

    @Test
    fun `between two equally near neighbours the following one takes the centre`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3), centre = 1, after = listOf(1, 3))).isEqualTo(1)
    }

    @Test
    fun `the last album hidden hands the centre to the one before it`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3), centre = 2, after = listOf(1, 2))).isEqualTo(1)
    }

    @Test
    fun `with nothing in common the centre stays in place, within the new list`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3), centre = 2, after = listOf(7, 8))).isEqualTo(1)
        assertThat(VisualCollectionArtwork.keptCentre(emptyList(), centre = 0, after = listOf(7, 8))).isEqualTo(0)
    }

    @Test
    fun `an empty list keeps the centre at the start`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2), centre = 1, after = emptyList<Int>())).isEqualTo(0)
    }

    @Test
    fun `a centre out of range is read within the old list`() {
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3), centre = 9, after = listOf(1, 3))).isEqualTo(1)
        assertThat(VisualCollectionArtwork.keptCentre(listOf(1, 2, 3), centre = -4, after = listOf(1, 3))).isEqualTo(0)
    }
}
