package io.sonora.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeTintTest {

    private val teal = ArtworkPalette.from(0xFF8FD3C4.toInt())
    private val olive = ArtworkPalette.from(0xFFFFF27A.toInt())

    @Test
    fun `no page tints the chrome by default`() {
        assertThat(ChromeTint().palette).isNull()
    }

    @Test
    fun `the newest page's tint wins and leaving it returns to the one below`() {
        val tint = ChromeTint()
        val albumA = Any()
        val albumB = Any()

        tint.set(albumA, teal)
        tint.set(albumB, olive)
        assertThat(tint.palette).isEqualTo(olive)

        tint.clear(albumB)
        assertThat(tint.palette).isEqualTo(teal)

        tint.clear(albumA)
        assertThat(tint.palette).isNull()
    }

    @Test
    fun `a page updating its tint keeps its place, and clearing a page that left below does not touch the top`() {
        val tint = ChromeTint()
        val albumA = Any()
        val albumB = Any()
        tint.set(albumA, teal)
        tint.set(albumB, teal)

        tint.set(albumB, olive)
        tint.clear(albumA)

        assertThat(tint.palette).isEqualTo(olive)
    }

    @Test
    fun `a page without a tint yet does not hide the one below`() {
        val tint = ChromeTint()
        val albumA = Any()
        tint.set(albumA, teal)
        tint.set(Any(), null)

        assertThat(tint.palette).isEqualTo(teal)
    }

    @Test
    fun `the backdrop under a large screen's sidebar follows the page's palette`() {
        val tint = ChromeTint()
        assertThat(tint.backdrop).isNull()

        val album = Any()
        tint.set(album, teal)
        assertThat(tint.backdrop).isEqualTo(teal)

        tint.clear(album)
        assertThat(tint.backdrop).isNull()
    }

    @Test
    fun `a page still loading its colours has a backdrop but does not tint the chrome`() {
        val tint = ChromeTint()
        val neutral = ArtworkPalette.from(null)
        val loading = Any()

        tint.set(loading, palette = null, backdrop = neutral)

        assertThat(tint.palette).isNull()
        assertThat(tint.backdrop).isEqualTo(neutral)
    }

    @Test
    fun `the newest backdrop wins and leaving it returns to the one below`() {
        val tint = ChromeTint()
        val albumA = Any()
        val albumB = Any()
        tint.set(albumA, teal)
        tint.set(albumB, palette = null, backdrop = olive)

        assertThat(tint.backdrop).isEqualTo(olive)
        assertThat(tint.palette).isEqualTo(teal)

        tint.clear(albumB)
        assertThat(tint.backdrop).isEqualTo(teal)
    }
}
