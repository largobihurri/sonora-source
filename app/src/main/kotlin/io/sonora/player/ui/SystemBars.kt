package io.sonora.player.ui

import io.sonora.core.designsystem.layout.WindowClass

/**
 * Whether the window asks the system to keep the navigation bar legible by itself (`isNavigationBarContrastEnforced`).
 * With it on, a bar over content whose colour the system cannot know gets a translucent scrim in the bar's own light or
 * dark: on a tablet that is a light band under a dark tinted page (an album page, Now Playing) while the app theme is
 * light. A large screen draws edge to edge instead, so the page's colour runs under the bar; a phone keeps what it has.
 */
internal fun navigationContrastEnforced(windowClass: WindowClass): Boolean = !windowClass.isLarge
