package io.sonora.core.designsystem.theme

import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppearancePreferences

/** The accent (packed ARGB) for each theme, each readable on that theme's backgrounds. */
data class AccentPair(val light: Int, val dark: Int) {
    fun forTheme(dark: Boolean): Int = if (dark) this.dark else light
}

/**
 * Turns the appearance preferences into the app's accent. Every result is contrast-adjusted to WCAG AA on the
 * backgrounds the accent is drawn on, so a pale wallpaper or artwork colour still reads as text.
 */
object AccentResolver {
    /** Wallpaper (Material You) colours exist from Android 12. */
    const val DYNAMIC_COLOR_MIN_SDK = 31

    /** Where accent text and icons sit: the page background and the grouped cards. */
    val LIGHT_BACKGROUNDS: List<Int> = listOf(0xFFFFFFFF.toInt(), 0xFFF2F2F7.toInt())
    val DARK_BACKGROUNDS: List<Int> = listOf(0xFF000000.toInt(), 0xFF1C1C1E.toInt())

    /** iOS system colours, plus Sonora's own pink; adjusted per theme by [preset]. */
    private fun base(preset: AccentPreset): Int = when (preset) {
        AccentPreset.RED -> 0xFFFF3B30
        AccentPreset.ORANGE -> 0xFFFF9500
        AccentPreset.AMBER -> 0xFFFFB800
        AccentPreset.GREEN -> 0xFF34C759
        AccentPreset.TEAL -> 0xFF30B0C7
        AccentPreset.BLUE -> 0xFF007AFF
        AccentPreset.INDIGO -> 0xFF5856D6
        AccentPreset.PURPLE -> 0xFFAF52DE
        AccentPreset.PINK -> 0xFFFA2D48
    }.toInt()

    private val presets: Map<AccentPreset, AccentPair> = AccentPreset.entries.associateWith { adjusted(base(it), base(it)) }

    /** Sonora's default accent: the pink preset. */
    val Default: AccentPair get() = preset(AccentPreset.PINK)

    fun preset(preset: AccentPreset): AccentPair = presets.getValue(preset)

    /**
     * The accent for [appearance] on Android [sdkInt]. [wallpaperAccent] gives the Material You colour for a dark or
     * light theme (`null` when there is none); [artworkSeed] is the playing artwork's colour, if any.
     */
    fun resolve(appearance: AppearancePreferences, sdkInt: Int, wallpaperAccent: (dark: Boolean) -> Int?, artworkSeed: Int?): AccentPair =
        when (appearance.accentMode) {
            AccentMode.PRESET -> preset(appearance.preset)
            AccentMode.ARTWORK -> artworkSeed?.let { adjusted(it, it) } ?: preset(appearance.preset)
            AccentMode.MATERIAL_YOU -> {
                if (sdkInt < DYNAMIC_COLOR_MIN_SDK) {
                    Default
                } else {
                    val light = wallpaperAccent(false)
                    val dark = wallpaperAccent(true)
                    if (light == null || dark == null) Default else adjusted(light, dark)
                }
            }
        }

    private fun adjusted(light: Int, dark: Int) = AccentPair(
        light = ColorMath.ensureContrast(light, LIGHT_BACKGROUNDS),
        dark = ColorMath.ensureContrast(dark, DARK_BACKGROUNDS),
    )
}
