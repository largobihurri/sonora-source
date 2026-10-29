package io.sonora.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import kotlinx.coroutines.delay

/** A short confirmation such as "Added to Queue"; [id] makes a repeated message restart its timer. */
data class BannerMessage(val text: String, val icon: ImageVector, val id: Long)

/** Holds the confirmation shown by [ConfirmationBanner]; a newer message replaces the visible one. */
@Stable
class BannerState {
    var message by mutableStateOf<BannerMessage?>(null)
        private set

    /** The latest message, kept while the banner animates out. */
    var lastShown: BannerMessage? = null
        private set
    private var nextId = 0L

    /** Drives the banner's enter and exit animation. */
    internal val visibility = MutableTransitionState(false)

    /** The banner is visible or still animating out: the backdrop behind it is worth sampling. */
    val onScreen: Boolean
        get() = visibility.targetState || visibility.currentState || !visibility.isIdle

    fun show(text: String, icon: ImageVector) {
        message = BannerMessage(text, icon, nextId++).also { lastShown = it }
        visibility.targetState = true
    }

    fun dismiss(shown: BannerMessage) {
        if (message == shown) {
            message = null
            visibility.targetState = false
        }
    }
}

@Composable
fun rememberBannerState(): BannerState = remember { BannerState() }

/**
 * Transient translucent capsule announcing [BannerState.message] for [visibleMillis], then fading away.
 * Its translucency samples [backdrop]: content the banner floats over, recorded while [BannerState.onScreen].
 */
@Composable
fun ConfirmationBanner(
    state: BannerState,
    modifier: Modifier = Modifier,
    visibleMillis: Long = 1_600L,
    backdrop: TranslucentBackdrop? = LocalTranslucentBackdrop.current,
) {
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(visibleMillis)
            state.dismiss(message)
        }
    }
    AnimatedVisibility(
        visibleState = state.visibility,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.8f) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
    ) {
        val shown = state.lastShown ?: return@AnimatedVisibility
        TranslucentSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, backdrop = backdrop) {
            val colors = LocalTranslucentContentColors.current
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(shown.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(shown.text, style = MaterialTheme.typography.titleMedium, color = colors.label)
            }
        }
    }
}
