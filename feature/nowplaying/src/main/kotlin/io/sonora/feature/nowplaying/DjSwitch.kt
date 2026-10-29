package io.sonora.feature.nowplaying

/*
 * The DJ on/off transition (D2.10): Now Playing swaps its standard arrangement for the DJ one (or back) over about
 * 300 ms instead of in one frame. The two arrangements are composed side by side for the length of the swap, one fading
 * out while shrinking a little, the other fading in from slightly larger, and only one stays afterwards. This file is the
 * pure part: which layers are composed (SwitchPlan) and how each is drawn at a point of the swap (switchFrame).
 */

/** How long the swap lasts at the system's normal animator scale (the scale stretches or removes it). */
internal const val DJ_SWITCH_MILLIS = 300

/** One composed arrangement of a switch: [value] is what it was composed with, [key] decides whether a change swaps it. */
internal data class SwitchLayer<T>(val id: Int, val key: Any, val value: T)

/** What a layer is doing in the swap. */
internal enum class SwitchRole {
    /** The only layer: drawn as it is. */
    Settled,

    /** The arrangement the swap goes to. */
    Incoming,

    /** The arrangement the swap leaves: fading away, its state frozen. */
    Outgoing,
}

/**
 * The layers composed by a switch, oldest first: one while settled, two during a swap (the outgoing one, then the
 * incoming one, which is the [target]). Immutable; every change is a new plan, and an unchanged one is the same instance.
 * Layer ids are never reused, so a new id means a new swap to animate.
 */
internal data class SwitchPlan<T>(val layers: List<SwitchLayer<T>>, private val nextId: Int) {

    /** Whether a swap is under way (two layers are composed). */
    val transitioning: Boolean get() = layers.size > 1

    /** The newest layer: the arrangement the screen is going to or is on. */
    val target: SwitchLayer<T> get() = layers.last()

    fun roleOf(layer: SwitchLayer<T>): SwitchRole = when {
        !transitioning -> SwitchRole.Settled
        layer.id == target.id -> SwitchRole.Incoming
        else -> SwitchRole.Outgoing
    }

    /**
     * The plan after the screen asks for [value] under [key]. The same key as the target's only brings the target's value
     * up to date (the arrangement stays composed and recomposes). A new key adds an incoming layer over the target, which
     * becomes the outgoing one; a swap still running is cut short, dropping the layer that was already leaving, so at most
     * two layers are ever composed.
     */
    fun retarget(value: T, key: Any): SwitchPlan<T> {
        val last = target
        if (last.key == key) {
            return if (last.value == value) this else copy(layers = layers.dropLast(1) + last.copy(value = value))
        }
        return SwitchPlan(listOf(last, SwitchLayer(nextId, key, value)), nextId + 1)
    }

    /** The swap is over: only the target stays. */
    fun settled(): SwitchPlan<T> = if (transitioning) copy(layers = listOf(target)) else this

    companion object {
        fun <T> of(value: T, key: Any): SwitchPlan<T> = SwitchPlan(listOf(SwitchLayer(0, key, value)), nextId = 1)
    }
}

/**
 * How a layer is drawn: its [alpha], its [scale] about its centre and its vertical [shift] as a fraction of its height
 * (positive is down).
 */
internal data class SwitchFrame(val alpha: Float, val scale: Float, val shift: Float)

/**
 * The frame of a layer in [role] at [progress] (0 to 1, by time) of the swap. The outgoing layer is gone by half way and
 * the incoming one only starts to show at about a third of it, so the two are never both on show and their text never
 * overlaps; meanwhile the outgoing one shrinks and rises by a few percent and the incoming one settles from a few percent
 * larger and lower. A [calm] swap (large windows, T13) keeps every layer at its size and place and only fades it.
 */
internal fun switchFrame(role: SwitchRole, progress: Float, calm: Boolean = false): SwitchFrame {
    val frame = movingFrame(role, progress)
    return if (calm) frame.copy(scale = 1f, shift = 0f) else frame
}

private fun movingFrame(role: SwitchRole, progress: Float): SwitchFrame {
    val p = progress.coerceIn(0f, 1f)
    val eased = smoothStep(0f, 1f, p)
    return when (role) {
        SwitchRole.Settled -> SwitchFrame(alpha = 1f, scale = 1f, shift = 0f)
        SwitchRole.Outgoing -> SwitchFrame(
            alpha = 1f - smoothStep(0f, OUT_FADE_END, p),
            scale = 1f - SCALE_TRAVEL * eased,
            shift = 0f - SHIFT_TRAVEL * eased, // 0f - x, not -x: no negative zero at the start
        )
        SwitchRole.Incoming -> SwitchFrame(
            alpha = smoothStep(IN_FADE_START, 1f, p),
            scale = 1f + SCALE_TRAVEL * (1f - eased),
            shift = SHIFT_TRAVEL * (1f - eased),
        )
    }
}

/**
 * The alpha a layer in [role] is drawn at, at [progress] of the swap. A [calm] swap (large windows) never lets the incoming layer
 * go fully clear: a layer with no alpha is skipped by the renderer, so its first frames (about 40 ms of GPU on the Pad 6) would
 * be drawn while it fades in; at [CALM_FLOOR_ALPHA] it is invisible but already drawn when the fade starts.
 */
internal fun swapAlpha(role: SwitchRole, progress: Float, calm: Boolean): Float {
    val alpha = switchFrame(role, progress, calm).alpha
    return if (calm && role == SwitchRole.Incoming) maxOf(alpha, CALM_FLOOR_ALPHA) else alpha
}

/** What the incoming layer of a calm swap is drawn at while it is meant to be clear: invisible, but not skipped. */
internal const val CALM_FLOOR_ALPHA = 0.02f

/** How many frames the incoming layer of a calm swap is drawn, clear, before the swap's clock starts (large windows, T13). */
internal const val DJ_SWITCH_WARM_FRAMES = 3

private const val OUT_FADE_END = 0.5f
private const val IN_FADE_START = 0.35f
private const val SCALE_TRAVEL = 0.04f
private const val SHIFT_TRAVEL = 0.02f

/** 0 below [from], 1 above [to], an S curve between. */
private fun smoothStep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
