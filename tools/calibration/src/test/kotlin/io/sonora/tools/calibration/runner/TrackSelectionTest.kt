package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackSelectionTest {

    private val library: List<SelectionCandidate> =
        (0 until 20).map { SelectionCandidate("mp3/$it.mp3", "mp3", 80.0 + it * 5, variable = false) } +
            (0 until 10).map { SelectionCandidate("wav/$it.wav", "wav", 120.0 + it, variable = false) } +
            (0 until 4).map { SelectionCandidate("flac/$it.flac", "flac", 170.0 + it, variable = false) } +
            listOf(SelectionCandidate("m4a/0.m4a", "m4a", 100.0, variable = false)) +
            (0 until 3).map { SelectionCandidate("mp3/var$it.mp3", "mp3", 172.0, variable = true) }

    @Test
    fun `returns the requested count`() {
        assertThat(TrackSelection.spread(library, 12, includeVariable = true)).hasSize(12)
        assertThat(TrackSelection.spread(library, 5, includeVariable = false)).hasSize(5)
    }

    @Test
    fun `asking for more than there is returns everything once`() {
        val all = TrackSelection.spread(library, 500, includeVariable = true)
        assertThat(all).hasSize(library.size)
        assertThat(all.toSet()).hasSize(library.size)
    }

    @Test
    fun `every variable-tempo track is included when asked`() {
        val chosen = TrackSelection.spread(library, 8, includeVariable = true)
        assertThat(chosen.filter { it.variable }).hasSize(3)
    }

    @Test
    fun `without the variable request they are not forced in`() {
        val chosen = TrackSelection.spread(library, 4, includeVariable = false)
        assertThat(chosen.none { it.variable }).isTrue()
    }

    @Test
    fun `every format is represented when the count allows it`() {
        val chosen = TrackSelection.spread(library, 5, includeVariable = false)
        assertThat(chosen.map { it.format }.toSet()).containsExactly("mp3", "wav", "flac", "m4a")
    }

    @Test
    fun `picks within a format spread over its tempo range`() {
        val chosen = TrackSelection.spread(library, 9, includeVariable = false).filter { it.format == "mp3" }
        assertThat(chosen.size).isAtLeast(2)
        val bpms = chosen.map { it.bpm }
        assertThat(bpms).contains(80.0)
        assertThat(bpms).contains(175.0)
    }

    @Test
    fun `the choice is deterministic and independent of the input order`() {
        val a = TrackSelection.spread(library, 15, includeVariable = true)
        val b = TrackSelection.spread(library.reversed(), 15, includeVariable = true)
        assertThat(b).containsExactlyElementsIn(a).inOrder()
    }
}
