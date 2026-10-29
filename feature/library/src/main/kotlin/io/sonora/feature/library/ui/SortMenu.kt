package io.sonora.feature.library.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.component.TranslucentMenuCheckRow
import io.sonora.core.designsystem.component.TranslucentMenuHeader
import io.sonora.core.designsystem.component.TranslucentMenuSeparator
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.PxRect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.feature.library.R

/** A list's sort control: the [list] whose keys the menu offers, its current [order] and what picking one does. */
internal data class SortControl(val list: SortList, val order: SortOrder, val onSelect: (SortOrder) -> Unit)

/**
 * The top bar's Sort button (a translucent circle) and its pull-down menu, Apple Music style: a translucent menu
 * hanging under the button (over the blurred, dimmed app, like the context menu) with the list's keys and a check on
 * the current one, then Ascending / Descending (hidden for Playlist Order, which has no direction). Tapping outside
 * or Back closes it.
 */
@Composable
internal fun SortButton(control: SortControl, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<PxRect?>(null) }
    val order = control.order
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            anchor = PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        },
    ) {
        TranslucentBarCircle(SonoraIcons.Sort, stringResource(R.string.sort_menu), onClick = { open = true })
    }
    if (open) {
        TranslucentPanel(stringResource(R.string.sort_menu), onDismiss = { open = false }, anchor = anchor) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                TranslucentMenuHeader(stringResource(R.string.sort_by))
                for (key in control.list.keys) {
                    TranslucentMenuCheckRow(stringResource(sortKeyLabel(key)), selected = key == order.key) {
                        open = false
                        control.onSelect(order.withKey(key))
                    }
                }
                if (order.key.hasDirection) {
                    TranslucentMenuSeparator()
                    for (direction in SortDirection.entries) {
                        TranslucentMenuCheckRow(stringResource(sortDirectionLabel(direction)), selected = direction == order.direction) {
                            open = false
                            control.onSelect(order.withDirection(direction))
                        }
                    }
                }
            }
        }
    }
}

@StringRes
internal fun sortKeyLabel(key: SortKey): Int = when (key) {
    SortKey.PLAYLIST_ORDER -> R.string.sort_key_playlist_order
    SortKey.TITLE -> R.string.sort_key_title
    SortKey.NAME -> R.string.sort_key_name
    SortKey.ARTIST -> R.string.sort_key_artist
    SortKey.ALBUM -> R.string.sort_key_album
    SortKey.GENRE -> R.string.sort_key_genre
    SortKey.RELEASE_DATE -> R.string.sort_key_release_date
    SortKey.DATE_ADDED -> R.string.sort_key_date_added
    SortKey.DATE_CONVERTED -> R.string.sort_key_date_converted
    SortKey.DURATION -> R.string.sort_key_duration
}

@StringRes
internal fun sortDirectionLabel(direction: SortDirection): Int = when (direction) {
    SortDirection.ASCENDING -> R.string.sort_ascending
    SortDirection.DESCENDING -> R.string.sort_descending
}
