package io.sonora.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.sort.SortList
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.SelectionBar
import io.sonora.feature.library.ui.SelectionBarSpace
import io.sonora.feature.library.ui.SelectionHeader
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.SortControl
import io.sonora.feature.library.ui.keyedByUri
import io.sonora.feature.library.ui.dragSelect
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection

/**
 * Library > Converted: every song the converter produced that still exists, in date sections like the converter
 * history when sorted by date converted, else as one sorted list. Play / Shuffle cover the whole list as shown; a
 * tapped song plays the list from it.
 */
@Composable
internal fun ConvertedMusicScreen(
    onBack: () -> Unit,
    onSongMenu: (Track) -> Unit,
    viewModel: ConvertedMusicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasSongs = state.tracks.isNotEmpty()
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    val title = stringResource(R.string.library_converted)
    val selection = rememberSongSelection()
    // In the order shown: the history's sections one after another.
    val shown = remember(state.groups) { state.groups.flatMap { it.tracks }.keyedByUri() }
    val selecting = selection?.active == true
    TranslucentBarPage(
        bar = {
            if (selecting) {
                SelectionHeader(selection, shown.map { it.first })
            } else {
                BackBar(
                    stringResource(R.string.library_back_library), onBack, onMore = null,
                    sort = SortControl(SortList.CONVERTED, state.order, viewModel::setSort),
                    onSelect = selection?.takeIf { hasSongs }?.let { { it.start() } },
                )
            }
        },
        scrollEdge = selecting,
        overlay = {
            if (selection != null) SelectionBar(selection, shown, title, Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding))
        },
    ) {
        ContentWidthLazyColumn(
            Modifier.fillMaxSize().dragSelect(selection, listState, shown),
            state = listState,
            contentPadding = PaddingValues(bottom = BottomChromePadding + if (selecting) SelectionBarSpace else 0.dp),
            max = ContentWidth.TABLE_DP.dp,
        ) {
            item {
                TranslucentTopBarSpace()
                LargeTitle(title)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PillButton(stringResource(R.string.library_play), SonoraIcons.Play, { viewModel.play() }, Modifier.weight(1f), enabled = hasSongs)
                    PillButton(stringResource(R.string.library_shuffle), SonoraIcons.Shuffle, viewModel::shuffle, Modifier.weight(1f), enabled = hasSongs)
                }
                InsetDivider(start = 20.dp)
                if (!state.loading && !hasSongs) {
                    EmptyMessage(stringResource(R.string.library_converted_empty))
                }
            }
            for (group in state.groups) {
                val section = group.section
                if (section != null) {
                    item(key = "section:${section.name}") {
                        Text(
                            stringResource(sectionTitle(section)),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                }
                items(group.tracks, key = { "track:${it.id}" }) { track ->
                    SongRow(
                        track,
                        onClick = selection?.click(track.uri) { viewModel.play(track) } ?: { viewModel.play(track) },
                        onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                        pinned = track.uri in state.pinned,
                        selected = selection?.mark(track.uri),
                        modifier = Modifier.dragSelectRow(selection, track.uri),
                    )
                }
            }
        }
    }
}

private fun sectionTitle(section: HistorySection): Int = when (section) {
    HistorySection.TODAY -> R.string.library_section_today
    HistorySection.YESTERDAY -> R.string.library_section_yesterday
    HistorySection.LAST_7_DAYS -> R.string.library_section_last_7_days
    HistorySection.LAST_30_DAYS -> R.string.library_section_last_30_days
    HistorySection.OLDER -> R.string.library_section_older
}
