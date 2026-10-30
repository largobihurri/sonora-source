package io.sonora.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.designsystem.component.TranslucentBarHeight
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.domain.library.duplicates.DuplicateScan
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.player.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The small in-app notice of the first duplicate analysis. */
sealed interface DuplicateNotice {
    /** [checked] of [total] files not checked yet are done ([total] 0 while the library is read). */
    data class Running(val checked: Int, val total: Int) : DuplicateNotice

    /** The analysis ended with [groups] duplicate groups. */
    data class Finished(val groups: Int) : DuplicateNotice

    companion object {
        /**
         * What the notice shows for the scan's [state]; nothing unless the first analysis is [announced], or if it
         * failed (a failure also ends the announcement, [DuplicateScan.announced], so no later scan brings it back).
         */
        fun of(state: DuplicateScanState, announced: Boolean): DuplicateNotice? = if (!announced) {
            null
        } else {
            when (state) {
                DuplicateScanState.NotScanned -> Running(0, 0)
                is DuplicateScanState.Scanning -> Running(state.checked, state.total)
                is DuplicateScanState.Found -> Finished(state.groups.size)
                DuplicateScanState.Failed -> null
            }
        }
    }
}

/** Follows the first duplicate analysis for its notice; closing the notice only hides it, the analysis goes on. */
@HiltViewModel
class DuplicateNoticeViewModel @Inject constructor(private val scan: DuplicateScan) : ViewModel() {
    val notice: Flow<DuplicateNotice?> = combine(scan.state, scan.announced, DuplicateNotice::of)

    fun close() = scan.closeAnnouncement()
}

/**
 * How far below the status bar the notice sits, in dp: under the top pill while it shows, otherwise under the
 * [TranslucentBarHeight] band where every screen keeps its back button and bar buttons, so it never covers them.
 */
internal fun duplicateNoticeTop(navigation: NavigationChrome, chromeVisible: Boolean): Float =
    if (navigation == NavigationChrome.TopPill && chromeVisible) TopPillHeight else TranslucentBarHeight.value

/**
 * A small closable translucent capsule at the top of the shell (clear of the mini player and the tab bar) while the
 * first duplicate analysis of a new library runs: "Analysing duplicates… 340/1,200", then what it found; tapping the
 * finished notice opens Duplicates ([onOpen]) and hides it. Closing hides it; the analysis goes on.
 */
@Composable
internal fun DuplicateNoticeHost(onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: DuplicateNoticeViewModel = hiltViewModel()) {
    val flow = remember(viewModel) { viewModel.notice }
    val notice by flow.collectAsStateWithLifecycle(initialValue = null)
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val shown = notice ?: return@AnimatedVisibility
        NoticeCapsule(
            shown,
            onOpen = {
                viewModel.close()
                onOpen()
            },
            onClose = viewModel::close,
        )
    }
}

@Composable
private fun NoticeCapsule(notice: DuplicateNotice, onOpen: () -> Unit, onClose: () -> Unit) {
    val locale = currentLocale()
    val text = when (notice) {
        is DuplicateNotice.Running -> if (notice.total > 0) {
            stringResource(R.string.duplicate_notice_running, LocaleFormats.integer(notice.checked, locale), LocaleFormats.integer(notice.total, locale))
        } else {
            stringResource(R.string.duplicate_notice_starting)
        }
        is DuplicateNotice.Finished -> if (notice.groups == 0) {
            stringResource(R.string.duplicate_notice_none)
        } else {
            pluralStringResource(R.plurals.duplicate_notice_finished, notice.groups, notice.groups)
        }
    }
    val finished = notice is DuplicateNotice.Finished
    TranslucentSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, overContent = true) {
        val colors = LocalTranslucentContentColors.current
        Row(
            Modifier
                .then(if (finished) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (finished) {
                Icon(SonoraIcons.Copy, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onClose) {
                Icon(SonoraIcons.Clear, contentDescription = stringResource(R.string.duplicate_notice_close), tint = colors.label, modifier = Modifier.size(18.dp))
            }
        }
    }
}
