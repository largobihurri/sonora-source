package io.sonora.core.designsystem.translucent

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** The app's [TranslucentPolicy]; flat until the settings are read (and in previews). */
val LocalTranslucentPolicy = compositionLocalOf { TranslucentPolicy.Flat }

/**
 * Content that translucent surfaces drawn above it sample: create it with [rememberTranslucentBackdrop], mark the
 * content with [translucentBackdropSource] and hand it to those surfaces ([LocalTranslucentBackdrop] or a `backdrop`
 * parameter). Never give it to a translucent surface inside that same content: a surface cannot sample itself.
 */
@Stable
class TranslucentBackdrop internal constructor(internal val layer: LayerBackdrop?)

/** The backdrop that [TranslucentSurface][io.sonora.core.designsystem.component.TranslucentSurface] samples by default. */
val LocalTranslucentBackdrop = compositionLocalOf<TranslucentBackdrop?> { null }

/**
 * A backdrop for the current [LocalTranslucentPolicy]. With flat translucency nothing is recorded; otherwise one
 * graphics layer holds the content's last drawing, updated only when that content redraws (the library's recommended
 * structure), so still content costs nothing per frame.
 */
@Composable
fun rememberTranslucentBackdrop(): TranslucentBackdrop {
    if (!LocalTranslucentPolicy.current.samplesBackdrop) return remember { TranslucentBackdrop(null) }
    val layer = rememberLayerBackdrop()
    return remember(layer) { TranslucentBackdrop(layer) }
}

/**
 * Records this content (everything drawn after this modifier, so put the opaque background after it) into
 * [backdrop] while [enabled]; a no-op for flat translucency.
 */
fun Modifier.translucentBackdropSource(backdrop: TranslucentBackdrop, enabled: Boolean = true): Modifier {
    val layer = backdrop.layer ?: return this
    return if (enabled) layerBackdrop(layer) else this
}

/**
 * Colours readable on the translucent surface around them: [label] for text and glyphs, [accent] for the
 * accent-coloured ones.
 */
@Immutable
data class TranslucentContentColors(val label: Color, val accent: Color, val tier: TranslucentTier)

/** Set by each translucent surface for its content. */
val LocalTranslucentContentColors = staticCompositionLocalOf { TranslucentContentColors(Color.Black, Color.Black, TranslucentTier.FLAT) }

/**
 * Whether the user asked for more contrast: "High contrast text" (public on Android 16, read from the system setting
 * before), or the Medium / High "Contrast" level (Android 14+).
 */
fun Context.prefersHighContrast(): Boolean {
    val highContrastText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        getSystemService(AccessibilityManager::class.java)?.isHighContrastTextEnabled == true
    } else {
        try {
            Settings.Secure.getInt(contentResolver, HIGH_TEXT_CONTRAST_SETTING, 0) == 1
        } catch (_: SecurityException) {
            false // not readable for this app on this version
        }
    }
    if (highContrastText) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val contrast = getSystemService(UiModeManager::class.java)?.contrast ?: 0f
        if (contrast >= MORE_CONTRAST_LEVEL) return true
    }
    return false
}

/** Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, a system setting the SDK does not name. */
private const val HIGH_TEXT_CONTRAST_SETTING = "high_text_contrast_enabled"

/** UiModeManager.getContrast(): 0 standard, 0.5 medium, 1 high. */
private const val MORE_CONTRAST_LEVEL = 0.5f
