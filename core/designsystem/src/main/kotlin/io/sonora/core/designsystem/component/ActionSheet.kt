package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** One row of an [ActionSheet]. */
data class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** As narrow as an alert: a menu, not a page. */
private val ActionMenuWidth = 300.dp

/**
 * Translucent iOS context-menu style menu, centred over the blurred app in the [TranslucentPanel] layer: a [header]
 * describing the target (its text takes the colour for translucent surfaces by default), then the actions as
 * context-menu rows with their icons. [title] names it for accessibility. Choosing an action runs it and [onDismiss],
 * and the menu shrinks away; only the first choice runs ([SheetActionGate]). Back and a tap outside call [onDismiss].
 */
@Composable
fun ActionSheet(
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    title: String = "",
    header: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gate = remember { SheetActionGate() }
    ShowTranslucentPanel(title, onDismiss, anchor = null, maxWidth = ActionMenuWidth) {
        Column {
            CompositionLocalProvider(LocalContentColor provides LocalTranslucentContentColors.current.label) {
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) { header() }
            }
            Box(Modifier.weight(1f, fill = false)) {
                ContextMenuPanel(
                    top = emptyList(),
                    sections = listOf(
                        actions.map { action ->
                            ContextMenuItem(action.label, action.icon) {
                                // A double tap, or a second row tapped while the menu closes, runs nothing more.
                                gate.chooseNow(scope) {
                                    action.onClick()
                                    onDismiss()
                                }
                            }
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Runs [action] through this gate at once, within the tap: a menu has nothing to hide first (it shrinks away once
 * dismissed), so the first choice runs and every later one is ignored.
 */
internal fun SheetActionGate.chooseNow(scope: CoroutineScope, action: () -> Unit) {
    scope.launch(start = CoroutineStart.UNDISPATCHED) { choose(hide = {}, action = action) }
}
