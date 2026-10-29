package io.sonora.core.cast.ui

import androidx.annotation.OptIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi

/**
 * The Cast route chooser: Media3's route button (the Cast icon, filled while connected), tinted [tint]. A tap lists
 * the Cast devices on the network, or, while casting, offers to stop. Show it only where
 * [io.sonora.core.cast.CastSupport.isSupported]: it needs the Cast context that `CastSupport.initialize` loads.
 * TalkBack reads it with its connection state in the app's language: `strings_cast.xml` overrides Media3's
 * English-only `media_route_button_*` descriptions.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastRouteButton(tint: Color, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides tint) {
        MediaRouteButton(modifier)
    }
}
