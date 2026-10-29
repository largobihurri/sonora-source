package io.sonora.player.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.prefersHighContrast
import io.sonora.core.designsystem.motion.prefersReducedMotion

/**
 * The app's [TranslucentPolicy] for the user's Translucency switch ([translucency]) and the richest translucency this device's GPU has
 * kept up with ([ceiling], see [io.sonora.player.display.TranslucentCeiling]). The accessibility settings it honours
 * ("Remove animations", high-contrast text) are read again whenever the app comes back to the foreground, so a change
 * made in system settings applies on return.
 */
@Composable
internal fun rememberTranslucentPolicy(translucency: Boolean, ceiling: TranslucentTier): TranslucentPolicy {
    val context = LocalContext.current
    var reducedMotion by remember { mutableStateOf(context.prefersReducedMotion()) }
    var highContrast by remember { mutableStateOf(context.prefersHighContrast()) }
    LifecycleResumeEffect(context) {
        reducedMotion = context.prefersReducedMotion()
        highContrast = context.prefersHighContrast()
        onPauseOrDispose { }
    }
    return remember(translucency, ceiling, reducedMotion, highContrast) {
        TranslucentPolicy.resolve(Build.VERSION.SDK_INT, translucency, reducedMotion, highContrast, ceiling)
    }
}
