package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import org.junit.Test

class CueMarkerIndexTest {

    private fun hot(slot: Int, at: Double) = DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, null, CuePalette.defaultHot(slot), "")
    private fun memory(id: String, at: Double, color: CueColor = CuePalette.MEMORY_DEFAULT) =
        DjCue(id, CueKind.MEMORY, null, at, null, color, "")

    @Test
    fun `an empty index has no cues`() {
        assertThat(CueMarkerIndex.EMPTY.count).isEqualTo(0)
        assertThat(CueMarkerIndex.EMPTY.firstAtOrAfter(0.0)).isEqualTo(0)
    }

    @Test
    fun `cues are indexed by time whatever order they come in`() {
        val index = CueMarkerIndex(listOf(memory("b", 9_000.0), hot(2, 1_000.0), memory("a", 5_000.0)))

        assertThat(index.count).isEqualTo(3)
        assertThat((0 until 3).map(index::timeAt)).containsExactly(1_000.0, 5_000.0, 9_000.0).inOrder()
    }

    @Test
    fun `a hot cue has its pad letter and a memory cue none`() {
        val index = CueMarkerIndex(listOf(hot(2, 1_000.0), memory("a", 5_000.0)))

        assertThat(index.labelAt(0)).isEqualTo('C')
        assertThat(index.isHot(0)).isTrue()
        assertThat(index.labelAt(1)).isEqualTo('\u0000')
        assertThat(index.isHot(1)).isFalse()
    }

    @Test
    fun `the colour is the cue's rgb made opaque`() {
        val index = CueMarkerIndex(listOf(memory("a", 1.0, CueColor.custom(0x123456))))

        assertThat(index.colorAt(0)).isEqualTo(0xFF123456.toInt())
    }

    @Test
    fun `the first cue at or after a time is found by binary search`() {
        val index = CueMarkerIndex(listOf(hot(0, 1_000.0), memory("a", 5_000.0), memory("b", 9_000.0)))

        assertThat(index.firstAtOrAfter(0.0)).isEqualTo(0)
        assertThat(index.firstAtOrAfter(1_000.0)).isEqualTo(0)
        assertThat(index.firstAtOrAfter(1_000.1)).isEqualTo(1)
        assertThat(index.firstAtOrAfter(9_000.0)).isEqualTo(2)
        assertThat(index.firstAtOrAfter(9_000.1)).isEqualTo(3)
    }

    @Test
    fun `a saved loop has its end and a plain cue none`() {
        val loop = DjCue(DjCue.hotId(1), CueKind.HOT, 1, 1_000.0, 3_000.0, CuePalette.defaultHot(1), "")
        val index = CueMarkerIndex(listOf(loop, memory("a", 5_000.0)))

        assertThat(index.hasLoop(0)).isTrue()
        assertThat(index.loopEndAt(0)).isEqualTo(3_000.0)
        assertThat(index.hasLoop(1)).isFalse()
    }

    @Test
    fun `the span of a loop reaches back to the first cue whose loop is still in the view`() {
        // A long loop that starts well before the view still has to be drawn through it.
        val long = DjCue(DjCue.hotId(0), CueKind.HOT, 0, 1_000.0, 90_000.0, CuePalette.defaultHot(0), "")
        val index = CueMarkerIndex(listOf(long, memory("a", 40_000.0)))

        assertThat(index.longestLoopMs).isEqualTo(89_000.0)
        assertThat(CueMarkerIndex.EMPTY.longestLoopMs).isEqualTo(0.0)
    }
}
