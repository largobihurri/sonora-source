package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import org.junit.Test

/** Saved loops are cues with a loop end (D4.1's model): a hot cue on a pad, or a memory cue. */
class LoopCuesTest {

    private fun hot(slot: Int, at: Double) = DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, null, CuePalette.defaultHot(slot), "")

    @Test
    fun `a hot loop is the pad's cue with the loop's end, in the pad's colour`() {
        val cues = LoopCues.withHotLoop(emptyList(), slot = 2, inMs = 1_000.0, outMs = 3_000.0)

        val cue = DjCues.hotCue(cues, 2)!!
        assertThat(cue.kind).isEqualTo(CueKind.HOT)
        assertThat(cue.positionMs).isEqualTo(1_000.0)
        assertThat(cue.loopEndMs).isEqualTo(3_000.0)
        assertThat(cue.isLoop).isTrue()
        assertThat(cue.color).isEqualTo(CuePalette.defaultHot(2))
    }

    @Test
    fun `a hot loop only fills an empty pad`() {
        val before = listOf(hot(2, 500.0))

        assertThat(LoopCues.withHotLoop(before, slot = 2, inMs = 1_000.0, outMs = 3_000.0)).isEqualTo(before)
    }

    @Test
    fun `a hot loop leaves the other pads alone and keeps the cues sorted`() {
        val cues = LoopCues.withHotLoop(listOf(hot(0, 5_000.0), hot(1, 100.0)), slot = 2, inMs = 1_000.0, outMs = 3_000.0)

        assertThat(cues.map { it.id }).containsExactly("hot-1", "hot-2", "hot-0").inOrder()
    }

    @Test
    fun `a loop that does not end after it starts is not saved`() {
        assertThat(LoopCues.withHotLoop(emptyList(), 0, 2_000.0, 2_000.0)).isEmpty()
        assertThat(LoopCues.withMemoryLoop(emptyList(), "mem-1", 3_000.0, 2_000.0)).isEmpty()
    }

    @Test
    fun `a memory loop is a memory cue at the loop's start with its end`() {
        val cues = LoopCues.withMemoryLoop(emptyList(), "mem-1", 1_000.0, 3_000.0)

        val cue = cues.single()
        assertThat(cue.kind).isEqualTo(CueKind.MEMORY)
        assertThat(cue.slot).isNull()
        assertThat(cue.positionMs).isEqualTo(1_000.0)
        assertThat(cue.loopEndMs).isEqualTo(3_000.0)
    }

    @Test
    fun `a memory loop on a memory cue turns that cue into the loop, keeping its name and colour`() {
        val plain = DjCue("mem-0", CueKind.MEMORY, null, 1_002.0, null, CuePalette.COLORS[5], "drop")

        val cue = LoopCues.withMemoryLoop(listOf(plain), "mem-1", 1_000.0, 3_000.0).single()

        assertThat(cue.id).isEqualTo("mem-0")
        assertThat(cue.name).isEqualTo("drop")
        assertThat(cue.color).isEqualTo(CuePalette.COLORS[5])
        assertThat(cue.positionMs).isEqualTo(1_000.0)
        assertThat(cue.loopEndMs).isEqualTo(3_000.0)
    }

    @Test
    fun `the same memory loop twice is one`() {
        val once = LoopCues.withMemoryLoop(emptyList(), "mem-1", 1_000.0, 3_000.0)

        assertThat(LoopCues.withMemoryLoop(once, "mem-2", 1_000.0, 3_000.0)).isEqualTo(once)
    }

    @Test
    fun `a memory loop beside a hot cue is its own cue`() {
        val cues = LoopCues.withMemoryLoop(listOf(hot(0, 1_000.0)), "mem-1", 1_000.0, 3_000.0)

        assertThat(cues).hasSize(2)
    }

    @Test
    fun `the id a new memory loop is saved under is the cue that holds it`() {
        val cues = LoopCues.withMemoryLoop(emptyList(), "mem-1", 1_000.0, 3_000.0)

        assertThat(LoopCues.memoryLoopId(cues, "mem-1", 1_000.0)).isEqualTo("mem-1")
        val plain = DjCue("mem-0", CueKind.MEMORY, null, 1_002.0, null, CuePalette.COLORS[5], "")
        val upgraded = LoopCues.withMemoryLoop(listOf(plain), "mem-1", 1_000.0, 3_000.0)
        assertThat(LoopCues.memoryLoopId(upgraded, "mem-1", 1_000.0)).isEqualTo("mem-0")
    }
}
