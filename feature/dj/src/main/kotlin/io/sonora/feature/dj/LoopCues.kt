package io.sonora.feature.dj

import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import kotlin.math.abs

/**
 * Saved loops, which D4.1's model keeps as cues with a loop end: a hot loop is the hot cue of a pad, a memory loop a
 * memory cue (as rekordbox's PCOB/PCO2 entries do). Times are the grid's ([io.sonora.core.domain.playback.DjLoopState.gridInMs]),
 * the time cues and the XDJ export use. Like [DjCues], every function returns a new list and never mutates its input.
 */
internal object LoopCues {

    /** [cues] with pad [slot] holding the loop `[inMs, outMs)`; [cues] itself when the pad is set already or the loop is empty. */
    fun withHotLoop(cues: List<DjCue>, slot: Int, inMs: Double, outMs: Double): List<DjCue> {
        if (!(outMs > inMs) || DjCues.hotCue(cues, slot) != null) return cues
        val start = inMs.coerceAtLeast(0.0)
        val cue = DjCue(DjCue.hotId(slot), CueKind.HOT, slot, start, outMs, CuePalette.defaultHot(slot), "")
        return DjCues.sorted(cues + cue)
    }

    /**
     * [cues] plus a memory loop [id] over `[inMs, outMs)`. A memory cue already within [DjCues.DUPLICATE_TOLERANCE_MS] of In
     * (a plain one, or a loop) becomes this loop and keeps its id, name and colour, so a place has one memory cue.
     */
    fun withMemoryLoop(cues: List<DjCue>, id: String, inMs: Double, outMs: Double): List<DjCue> {
        if (!(outMs > inMs)) return cues
        val start = inMs.coerceAtLeast(0.0)
        val existing = memoryAt(cues, start)
        val loop = existing?.copy(positionMs = start, loopEndMs = outMs)
            ?: DjCue(id, CueKind.MEMORY, null, start, outMs, CuePalette.MEMORY_DEFAULT, "")
        return DjCues.sorted(cues.filterNot { it.id == existing?.id } + loop)
    }

    /** The id of the cue that holds the memory loop saved at [inMs] with the proposed [id]: an existing memory cue's when it took over. */
    fun memoryLoopId(cues: List<DjCue>, id: String, inMs: Double): String = memoryAt(cues, inMs.coerceAtLeast(0.0))?.id ?: id

    private fun memoryAt(cues: List<DjCue>, positionMs: Double): DjCue? =
        cues.firstOrNull { it.kind == CueKind.MEMORY && abs(it.positionMs - positionMs) <= DjCues.DUPLICATE_TOLERANCE_MS }
}
