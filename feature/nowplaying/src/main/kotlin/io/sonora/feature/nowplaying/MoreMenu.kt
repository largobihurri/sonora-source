package io.sonora.feature.nowplaying

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import io.sonora.core.designsystem.component.ContextMenuItem
import io.sonora.core.designsystem.component.ContextMenuPanel
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.PxRect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.domain.model.Track

/** What the "…" menu's rows do. A new row brings its callback here, set where Now Playing builds its [BottomRow]. */
internal data class MoreMenuActions(
    val onAddToPlaylist: (Track) -> Unit,
    /** Opens the system share sheet for the song playing. */
    val onShare: (Track) -> Unit = {},
    val onSleepTimer: () -> Unit = {},
    /** The A-B loop mode is on: its row shows a checkmark. */
    val abLoop: Boolean = false,
    val onAbLoop: () -> Unit = {},
    val onSpeedPitch: () -> Unit = {},
    /** DJ mode is on: its row shows a checkmark. */
    val djMode: Boolean = false,
    val onDjMode: () -> Unit = {},
    /** DUB mode is on: its row shows a checkmark. */
    val dubMode: Boolean = false,
    val onDubMode: () -> Unit = {},
    /**
     * Opens the beat grid editor, in DJ mode: `null` where there is no editor to open (the DJ arrangement is not the one
     * shown), which leaves the row out.
     */
    val onEditGrid: (() -> Unit)? = null,
    /** The song playing has an analysis whose grid can be edited; the row is dimmed until it has. */
    val gridEditable: Boolean = false,
    /**
     * Asks for the song to be analysed again, in DJ mode (the screen confirms first when the grid was edited): `null` where
     * there is no DJ screen to do it, which leaves the row out.
     */
    val onReanalyse: (() -> Unit)? = null,
    /** The song has settled (analysed or failed) and nothing is analysing it; the row is dimmed until then. */
    val canReanalyse: Boolean = false,
)

/**
 * One row of the "…" menu: its [label], [icon], whether it is [enabled], whether a mode it toggles is on ([checked],
 * null for a plain action) and what a tap does (the menu closes first).
 */
internal class MoreMenuEntry(
    @StringRes val label: Int,
    val icon: () -> ImageVector,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * The "…" menu's rows for the song playing ([track], null when nothing is), in sections the menu splits with thin
 * lines; in DJ mode the Speed & Pitch and A-B loop rows are left out (in DUB mode the A-B loop row), and "Edit beat grid" and "Analyse again" follow the DJ Mode row. To add a row: give [MoreMenuActions] its callback
 * and add a [MoreMenuEntry] to the section it belongs to (or a new section); [MoreButton] draws whatever this returns.
 */
internal fun moreMenuSections(track: Track?, actions: MoreMenuActions): List<List<MoreMenuEntry>> = listOf(
    listOf(
        MoreMenuEntry(R.string.now_playing_add_to_playlist, { SonoraIcons.Add }, enabled = track != null) {
            track?.let(actions.onAddToPlaylist)
        },
        MoreMenuEntry(R.string.now_playing_share, { SonoraIcons.Share }, enabled = track != null) {
            track?.let(actions.onShare)
        },
    ),
    buildList {
        add(MoreMenuEntry(R.string.sleep_timer_title, { MoonIcon }) { actions.onSleepTimer() })
        // DJ mode has its own tempo controls, and no A-B loop until its cues and loops (D4); DUB mode keeps DJ's loops, so
        // it has no A-B loop either.
        if (!actions.djMode) {
            add(MoreMenuEntry(R.string.speed_pitch_title, { SpeedIcon }) { actions.onSpeedPitch() })
            if (!actions.dubMode) {
                add(MoreMenuEntry(R.string.now_playing_ab_loop, { SonoraIcons.Repeat }, checked = actions.abLoop) { actions.onAbLoop() })
            }
        }
        add(MoreMenuEntry(R.string.now_playing_dj_mode, { DjIcon }, checked = actions.djMode) { actions.onDjMode() })
        // The beat grid editor lives in the DJ arrangement, and needs an analysis to edit.
        val editGrid = actions.onEditGrid
        if (actions.djMode && editGrid != null) {
            add(MoreMenuEntry(R.string.now_playing_edit_beat_grid, { BeatGridIcon }, enabled = actions.gridEditable) { editGrid() })
        }
        val reanalyse = actions.onReanalyse
        if (actions.djMode && reanalyse != null) {
            add(MoreMenuEntry(R.string.now_playing_reanalyse, { SonoraIcons.Refresh }, enabled = actions.canReanalyse) { reanalyse() })
        }
        // DUB mode last, after DJ mode's own rows; turning it on turns DJ mode off.
        add(MoreMenuEntry(R.string.now_playing_dub_mode, { DjIcon }, checked = actions.dubMode) { actions.onDubMode() })
    },
)

/**
 * "…" and its pull-down menu, iOS style: a translucent menu hanging under the button (over the blurred, dimmed app,
 * like the context menu) with the rows of [moreMenuSections]. Tapping outside or Back closes it, and so does a row
 * before it acts. The menu closes when its arrangement stops being shown (a turn starts).
 */
@Composable
internal fun MoreButton(track: Track?, actions: MoreMenuActions, button: @Composable (onClick: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<PxRect?>(null) }
    val shown = LocalArrangementRole.current() == ArrangementRole.Shown
    LaunchedEffect(shown) { if (!shown) open = false }
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            anchor = PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        },
    ) {
        button { open = true }
    }
    if (open && shown) {
        val sections = moreMenuSections(track, actions).map { section ->
            section.map { entry ->
                ContextMenuItem(stringResource(entry.label), entry.icon(), enabled = entry.enabled, checked = entry.checked) {
                    open = false
                    entry.onClick()
                }
            }
        }
        TranslucentPanel(stringResource(R.string.now_playing_more), onDismiss = { open = false }, anchor = anchor) {
            ContextMenuPanel(top = emptyList(), sections = sections)
        }
    }
}
