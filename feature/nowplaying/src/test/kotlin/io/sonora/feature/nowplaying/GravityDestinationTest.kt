package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.nowplaying.GravityDestination.Sideways
import io.sonora.feature.nowplaying.GravityDestination.Upright
import org.junit.Test

class GravityDestinationTest {

    @Test
    fun `a sideways detent, either way, makes the destination the visual collection`() {
        assertThat(Upright.after(detent = 1)).isEqualTo(Sideways)
        assertThat(Upright.after(detent = 3)).isEqualTo(Sideways)
        assertThat(Sideways.after(detent = 3)).isEqualTo(Sideways)
    }

    @Test
    fun `the upright detent, and upside down too, makes it the portrait screen again`() {
        assertThat(Sideways.after(detent = 0)).isEqualTo(Upright)
        assertThat(Sideways.after(detent = 2)).isEqualTo(Upright)
        assertThat(Upright.after(detent = 0)).isEqualTo(Upright)
    }

    @Test
    fun `between detents the destination does not change`() {
        assertThat(Upright.after(detent = null)).isEqualTo(Upright)
        assertThat(Sideways.after(detent = null)).isEqualTo(Sideways)
    }

    @Test
    fun `it latches, tilting away from a detent keeps it until another detent engages`() {
        var destination = Upright
        for (detent in listOf(0, null, null, 1, null, null, null)) destination = destination.after(detent)
        assertThat(destination).isEqualTo(Sideways)

        for (detent in listOf(null, 2, null)) destination = destination.after(detent)
        assertThat(destination).isEqualTo(Upright)

        for (detent in listOf(3, null, null)) destination = destination.after(detent)
        assertThat(destination).isEqualTo(Sideways)
    }

    @Test
    fun `opening Now Playing starts from where the window already is`() {
        assertThat(GravityDestination.opening(landscape = true)).isEqualTo(Sideways)
        assertThat(GravityDestination.opening(landscape = false)).isEqualTo(Upright)
    }

    @Test
    fun `a detent counts only while Now Playing heads open`() {
        // Open: latched from where it was (or from where the window was on opening).
        assertThat(GravityDestination.onDetent(latched = null, opening = Upright, detent = 1, open = true)).isEqualTo(Sideways)
        assertThat(GravityDestination.onDetent(latched = Sideways, opening = Upright, detent = 0, open = true)).isEqualTo(Upright)
        // Closing (sliding away) or closed: a detent caught meanwhile changes nothing, so the next opening starts from
        // the window's side, not from a stale latch.
        assertThat(GravityDestination.onDetent(latched = null, opening = Upright, detent = 1, open = false)).isNull()
        assertThat(GravityDestination.onDetent(latched = Upright, opening = Upright, detent = 3, open = false)).isEqualTo(Upright)
    }
}
