package io.sonora.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A window with its leading panel filled: the button that shows and hides the sidebar (24 dp grid, even-odd fill). */
internal val SidebarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sidebar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            // The frame (outer rounded rectangle minus the inner one) and, inside the hole, the filled panel.
            "M5,3h14a2,2 0 0 1 2,2v14a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V5a2,2 0 0 1 2,-2z" +
                "M5,5v14h14V5H5z" +
                "M6.5,6.5v11h3v-11h-3z",
        ),
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ).build()
}
