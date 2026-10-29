package io.sonora.core.djmodel

import kotlin.math.abs

/** The pure operations on a track's list of cues. Every one returns a new list sorted by position, never mutating its input. */
object DjCues {

    /** Memory cues closer together than this are one cue (a double tap does not make two). */
    const val DUPLICATE_TOLERANCE_MS: Double = 5.0

    /** Moving to the next or previous cue ignores cues this close to the position, so the cue just jumped to is not found again. */
    const val NAVIGATION_GUARD_MS: Double = 1.0

    private val ORDER: Comparator<DjCue> = compareBy<DjCue> { it.positionMs }
        .thenBy { if (it.kind == CueKind.HOT) 0 else 1 }
        .thenBy { it.slot ?: 0 }
        .thenBy { it.id }

    /** [cues] by position; at the same time hot cues come first, by pad, then memory cues. */
    fun sorted(cues: List<DjCue>): List<DjCue> = cues.sortedWith(ORDER)

    /** The hot cue on pad [slot], or `null` while it is empty. */
    fun hotCue(cues: List<DjCue>, slot: Int): DjCue? = cues.firstOrNull { it.kind == CueKind.HOT && it.slot == slot }

    /** [cues] with pad [slot] set at [positionMs]: a cue already on that pad is replaced (name and colour included). */
    fun withHotCue(
        cues: List<DjCue>,
        slot: Int,
        positionMs: Double,
        color: CueColor = CuePalette.defaultHot(slot),
        name: String = "",
    ): List<DjCue> {
        val cue = DjCue(DjCue.hotId(slot), CueKind.HOT, slot, positionMs, null, color, cleaned(name))
        return sorted(cues.filterNot { it.id == cue.id } + cue)
    }

    /**
     * [cues] plus a memory cue [id] at [positionMs]; [cues] itself (sorted) when a memory cue already stands within
     * [DUPLICATE_TOLERANCE_MS] of it. Hot cues never count: a memory cue may sit on a hot cue.
     */
    fun withMemoryCue(
        cues: List<DjCue>,
        id: String,
        positionMs: Double,
        color: CueColor = CuePalette.MEMORY_DEFAULT,
        name: String = "",
    ): List<DjCue> {
        if (cues.any { it.kind == CueKind.MEMORY && abs(it.positionMs - positionMs) <= DUPLICATE_TOLERANCE_MS }) return sorted(cues)
        return sorted(cues + DjCue(id, CueKind.MEMORY, null, positionMs, null, color, cleaned(name)))
    }

    /** [cues] without the cue [id]. */
    fun removed(cues: List<DjCue>, id: String): List<DjCue> = sorted(cues.filterNot { it.id == id })

    /** [cues] with the cue [id] renamed: trimmed and cut to [DjCue.MAX_NAME_LENGTH]. */
    fun renamed(cues: List<DjCue>, id: String, name: String): List<DjCue> =
        sorted(cues.map { if (it.id == id) it.copy(name = cleaned(name)) else it })

    /** [cues] with the cue [id] in [color]. */
    fun recoloured(cues: List<DjCue>, id: String, color: CueColor): List<DjCue> =
        sorted(cues.map { if (it.id == id) it.copy(color = color) else it })

    /** The first memory cue after [positionMs] (not the one the position is on), or `null` when there is none. */
    fun nextMemory(cues: List<DjCue>, positionMs: Double): DjCue? =
        sorted(cues).firstOrNull { it.kind == CueKind.MEMORY && it.positionMs > positionMs + NAVIGATION_GUARD_MS }

    /** The last memory cue before [positionMs] (not the one the position is on), or `null` when there is none. */
    fun previousMemory(cues: List<DjCue>, positionMs: Double): DjCue? =
        sorted(cues).lastOrNull { it.kind == CueKind.MEMORY && it.positionMs < positionMs - NAVIGATION_GUARD_MS }

    private fun cleaned(name: String): String = name.trim().take(DjCue.MAX_NAME_LENGTH)

    /** The memory cue nearest to [positionMs] and no further than [toleranceMs] from it (the earlier one on a tie), or `null`. */
    fun nearestMemory(cues: List<DjCue>, positionMs: Double, toleranceMs: Double): DjCue? =
        sorted(cues).filter { it.kind == CueKind.MEMORY && abs(it.positionMs - positionMs) <= toleranceMs }
            .minByOrNull { abs(it.positionMs - positionMs) }
}
