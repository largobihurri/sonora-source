package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DjCuesTest {

    private fun cues(): List<DjCue> = emptyList()

    @Test
    fun `setting an empty pad adds a hot cue with the pad's default colour`() {
        val result = DjCues.withHotCue(cues(), slot = 1, positionMs = 2_500.0)

        val cue = result.single()
        assertThat(cue.kind).isEqualTo(CueKind.HOT)
        assertThat(cue.slot).isEqualTo(1)
        assertThat(cue.id).isEqualTo("hot-1")
        assertThat(cue.positionMs).isEqualTo(2_500.0)
        assertThat(cue.color).isEqualTo(CuePalette.defaultHot(1))
        assertThat(cue.name).isEmpty()
        assertThat(cue.loopEndMs).isNull()
    }

    @Test
    fun `setting a pad that is taken replaces its cue and leaves the others`() {
        val first = DjCues.withHotCue(DjCues.withHotCue(cues(), 0, 1_000.0), 3, 9_000.0)

        val result = DjCues.withHotCue(first, 0, 4_000.0, name = "drop")

        assertThat(result.map { it.slot }).containsExactly(0, 3).inOrder()
        assertThat(result.first().positionMs).isEqualTo(4_000.0)
        assertThat(result.first().name).isEqualTo("drop")
        assertThat(result.last().positionMs).isEqualTo(9_000.0)
    }

    @Test
    fun `cues come back sorted by position, a hot cue before a memory cue at the same time`() {
        var list = cues()
        list = DjCues.withMemoryCue(list, "m1", 8_000.0)
        list = DjCues.withHotCue(list, 2, 3_000.0)
        list = DjCues.withMemoryCue(list, "m2", 3_000.0)
        list = DjCues.withHotCue(list, 0, 3_000.0)

        assertThat(list.map { it.id }).containsExactly("hot-0", "hot-2", "m2", "m1").inOrder()
    }

    @Test
    fun `sorting does not touch its input`() {
        val input = listOf(
            DjCue("b", CueKind.MEMORY, null, 9.0, null, CuePalette.MEMORY_DEFAULT, ""),
            DjCue("a", CueKind.MEMORY, null, 1.0, null, CuePalette.MEMORY_DEFAULT, ""),
        )

        val sorted = DjCues.sorted(input)

        assertThat(sorted.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(input.map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `a hot cue is found by its pad`() {
        val list = DjCues.withHotCue(cues(), 4, 100.0)

        assertThat(DjCues.hotCue(list, 4)!!.positionMs).isEqualTo(100.0)
        assertThat(DjCues.hotCue(list, 5)).isNull()
    }

    @Test
    fun `a memory cue is added and any number can exist`() {
        var list = cues()
        for (i in 0 until 50) list = DjCues.withMemoryCue(list, "m$i", i * 1_000.0)

        assertThat(list).hasSize(50)
        assertThat(list.all { it.kind == CueKind.MEMORY && it.slot == null }).isTrue()
    }

    @Test
    fun `a memory cue within a few milliseconds of another memory cue is not added again`() {
        val list = DjCues.withMemoryCue(cues(), "m1", 10_000.0)

        assertThat(DjCues.withMemoryCue(list, "m2", 10_004.0)).isEqualTo(list)
        assertThat(DjCues.withMemoryCue(list, "m3", 9_996.0)).isEqualTo(list)
        assertThat(DjCues.withMemoryCue(list, "m4", 10_006.0)).hasSize(2)
    }

    @Test
    fun `a hot cue at the same place does not stop a memory cue`() {
        val list = DjCues.withHotCue(cues(), 0, 5_000.0)

        assertThat(DjCues.withMemoryCue(list, "m1", 5_000.0)).hasSize(2)
    }

    @Test
    fun `a cue is removed by id and unknown ids change nothing`() {
        val list = DjCues.withMemoryCue(DjCues.withHotCue(cues(), 0, 1_000.0), "m1", 2_000.0)

        assertThat(DjCues.removed(list, "hot-0").map { it.id }).containsExactly("m1")
        assertThat(DjCues.removed(list, "nope")).isEqualTo(list)
    }

    @Test
    fun `a cue is renamed and recoloured in place keeping everything else`() {
        val list = DjCues.withHotCue(cues(), 0, 1_000.0)
        val blue = CuePalette.byIndex(10)!!

        val renamed = DjCues.renamed(list, "hot-0", "  Drop  ")
        val recoloured = DjCues.recoloured(renamed, "hot-0", blue)

        assertThat(renamed.single().name).isEqualTo("Drop")
        assertThat(recoloured.single().color).isEqualTo(blue)
        assertThat(recoloured.single().name).isEqualTo("Drop")
        assertThat(recoloured.single().positionMs).isEqualTo(1_000.0)
    }

    @Test
    fun `a name longer than the limit is cut`() {
        val list = DjCues.withHotCue(cues(), 0, 1_000.0)

        assertThat(DjCues.renamed(list, "hot-0", "y".repeat(100)).single().name).hasLength(DjCue.MAX_NAME_LENGTH)
    }

    @Test
    fun `the next memory cue is the first one after the position`() {
        var list = cues()
        for ((i, at) in listOf(1_000.0, 5_000.0, 9_000.0).withIndex()) list = DjCues.withMemoryCue(list, "m$i", at)

        assertThat(DjCues.nextMemory(list, 0.0)!!.id).isEqualTo("m0")
        assertThat(DjCues.nextMemory(list, 1_000.0)!!.id).isEqualTo("m1") // the cue we are on is not "next"
        assertThat(DjCues.nextMemory(list, 4_990.0)!!.id).isEqualTo("m1")
        assertThat(DjCues.nextMemory(list, 9_000.0)).isNull()
    }

    @Test
    fun `the previous memory cue is the last one before the position`() {
        var list = cues()
        for ((i, at) in listOf(1_000.0, 5_000.0, 9_000.0).withIndex()) list = DjCues.withMemoryCue(list, "m$i", at)

        assertThat(DjCues.previousMemory(list, 20_000.0)!!.id).isEqualTo("m2")
        assertThat(DjCues.previousMemory(list, 9_000.0)!!.id).isEqualTo("m1")
        assertThat(DjCues.previousMemory(list, 5_000.5)!!.id).isEqualTo("m0") // within the guard: still on m1
        assertThat(DjCues.previousMemory(list, 1_000.0)).isNull()
    }

    @Test
    fun `memory navigation ignores hot cues`() {
        val list = DjCues.withHotCue(cues(), 0, 2_000.0)

        assertThat(DjCues.nextMemory(list, 0.0)).isNull()
        assertThat(DjCues.previousMemory(list, 9_000.0)).isNull()
    }

    @Test
    fun `the nearest memory cue within the tolerance is found`() {
        var list = cues()
        for ((i, at) in listOf(1_000.0, 5_000.0, 5_200.0).withIndex()) list = DjCues.withMemoryCue(list, "m$i", at)

        assertThat(DjCues.nearestMemory(list, 1_100.0, 250.0)!!.id).isEqualTo("m0")
        assertThat(DjCues.nearestMemory(list, 5_150.0, 250.0)!!.id).isEqualTo("m2")
        assertThat(DjCues.nearestMemory(list, 5_100.0, 250.0)!!.id).isEqualTo("m1") // tie: the earlier
        assertThat(DjCues.nearestMemory(list, 3_000.0, 250.0)).isNull()
    }

    @Test
    fun `the nearest memory cue ignores hot cues`() {
        val list = DjCues.withHotCue(cues(), 0, 1_000.0)

        assertThat(DjCues.nearestMemory(list, 1_000.0, 250.0)).isNull()
    }
}
