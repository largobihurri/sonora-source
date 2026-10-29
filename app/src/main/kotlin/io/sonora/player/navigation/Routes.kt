package io.sonora.player.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.feature.cdjexport.ExportTabIcon
import io.sonora.player.R

/** Root navigation keys; the app shell shows one screen per [TopLevelRoute] tab. */
sealed interface Route

/** Destinations reachable from the bottom tab bar; [label] is a string resource. */
enum class TopLevelRoute(@param:StringRes val label: Int, val icon: ImageVector) : Route {
    Library(R.string.tab_library, SonoraIcons.Library),
    Search(R.string.tab_search, SonoraIcons.Search),
    Converter(R.string.tab_converter, SonoraIcons.Convert),
    Export(R.string.tab_export, ExportTabIcon),
}
