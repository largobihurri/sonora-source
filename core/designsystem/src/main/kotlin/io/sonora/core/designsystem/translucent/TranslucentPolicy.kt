package io.sonora.core.designsystem.translucent

/** How much of the translucency effect a translucent surface draws. */
enum class TranslucentTier {
    /** Lens refraction at the edges, blur, vibrancy and a specular rim (RuntimeShader, Android 13+). */
    LIQUID,

    /** Blur and vibrancy of the content behind, no refraction (RenderEffect, Android 12). */
    FROSTED,

    /** The flat translucent fill: Android 10-11, the Translucency switch off, or high-contrast text. */
    FLAT,
    ;

    /** One tier less translucent; flat stays flat. */
    fun stepDown(): TranslucentTier = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

/**
 * The translucency every surface of the app draws, decided in one place from the Android version, the Translucency
 * setting, the accessibility settings and what this device's GPU has shown it can keep up with. [highContrast] asks
 * for the more opaque flat surface; [specularMotion] lets the specular rim follow movement (off with "Remove
 * animations"); [limited] says the device ceiling, not Android or the user, is what lowered the tier (see
 * [TranslucentLoadPolicy]).
 */
data class TranslucentPolicy(
    val tier: TranslucentTier,
    val specularMotion: Boolean,
    val highContrast: Boolean,
    val limited: Boolean = false,
) {

    /** Whether surfaces sample the content behind them (a backdrop layer is worth recording). */
    val samplesBackdrop: Boolean get() = tier != TranslucentTier.FLAT

    companion object {
        /** RuntimeShader, needed by the lens refraction and the shaded rim. */
        const val REFRACTION_MIN_SDK = 33

        /** RenderEffect blur and colour filters. */
        const val BLUR_MIN_SDK = 31

        /** The light direction of the specular rim at rest, in degrees. */
        const val REST_LIGHT_ANGLE = 45f

        /** How far the light swings either way while something moves under it. */
        const val LIGHT_SWING = 30f

        /** Used until the settings are read, and in previews: never samples anything. */
        val Flat = TranslucentPolicy(TranslucentTier.FLAT, specularMotion = false, highContrast = false)

        /** The most translucent tier Android [sdkInt] can draw. */
        fun tierFor(sdkInt: Int): TranslucentTier = when {
            sdkInt >= REFRACTION_MIN_SDK -> TranslucentTier.LIQUID
            sdkInt >= BLUR_MIN_SDK -> TranslucentTier.FROSTED
            else -> TranslucentTier.FLAT
        }

        /**
         * The policy for Android [sdkInt] with the Translucency switch [enabled]. High-contrast text always gets the
         * flat, more opaque surface; [reducedMotion] keeps the translucency but holds its specular light still.
         * [ceiling] is the most translucent tier this device's GPU kept up with: it can lower the tier, never raise it.
         */
        fun resolve(
            sdkInt: Int,
            enabled: Boolean,
            reducedMotion: Boolean,
            highContrastText: Boolean,
            ceiling: TranslucentTier = TranslucentTier.LIQUID,
        ): TranslucentPolicy {
            val android = tierFor(sdkInt)
            val tier = when {
                !enabled || highContrastText -> TranslucentTier.FLAT
                else -> maxOf(android, ceiling)
            }
            return TranslucentPolicy(
                tier = tier,
                specularMotion = tier == TranslucentTier.LIQUID && !reducedMotion,
                highContrast = highContrastText,
                limited = enabled && !highContrastText && ceiling > android,
            )
        }

        /**
         * The specular light angle for a movement [shift] from -1 (start edge) to 1 (end edge); the rest angle when
         * [motion] is not allowed or the shift is unknown.
         */
        fun specularAngle(shift: Float, motion: Boolean): Float =
            if (!motion || shift.isNaN()) REST_LIGHT_ANGLE else REST_LIGHT_ANGLE + LIGHT_SWING * shift.coerceIn(-1f, 1f)
    }
}
