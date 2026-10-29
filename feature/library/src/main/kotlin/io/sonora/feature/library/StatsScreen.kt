package io.sonora.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.stats.RankedItem
import io.sonora.core.domain.stats.StatsRange
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Library > Statistics: a range (Week, Month, Year, All Time), the listening time and plays it added up to, when
 * statistics start, and the top ten artists, albums and songs.
 */
@Composable
internal fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val stats = state.stats
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    TranslucentBarPage(bar = { BackBar(stringResource(R.string.library_back_library), onBack, onMore = null) }) {
        ContentWidthLazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            item(key = StatsKeys.HEADER) {
                TranslucentTopBarSpace()
                LargeTitle(stringResource(R.string.stats_title))
                SegmentedControl(
                    options = StatsRange.entries,
                    selected = state.range,
                    label = { stringResource(it.label) },
                    onSelect = viewModel::select,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryTile(stringResource(R.string.stats_listening_time), listeningTimeText(stats.listenedMs, locale), Modifier.weight(1f))
                    SummaryTile(stringResource(R.string.stats_plays), LocaleFormats.integer(stats.plays, locale), Modifier.weight(1f))
                }
                val since = state.sinceMs
                if (since != null) {
                    val footer = if (state.range == StatsRange.ALL_TIME) " " + stringResource(R.string.stats_all_time_footer) else ""
                    Text(
                        stringResource(R.string.stats_since, dateText(since, locale)) + footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                if (!state.loading && stats.songs.isEmpty() && stats.plays == 0 && stats.listenedMs == 0L) {
                    EmptyMessage(stringResource(if (since == null) R.string.stats_nothing_yet else R.string.stats_nothing_in_range))
                }
            }
            ranking("artists", R.string.stats_top_artists, stats.artists, round = true)
            ranking("albums", R.string.stats_top_albums, stats.albums, round = false)
            ranking("songs", R.string.stats_top_songs, stats.songs, round = false)
        }
    }
}

private val StatsRange.label: Int
    get() = when (this) {
        StatsRange.WEEK -> R.string.stats_range_week
        StatsRange.MONTH -> R.string.stats_range_month
        StatsRange.YEAR -> R.string.stats_range_year
        StatsRange.ALL_TIME -> R.string.stats_range_all_time
    }

/**
 * The Statistics list's item keys: each kind of row has a prefix of its own, so a ranked item can never take a heading's
 * key (an artist called "Title" once did, and the list throws on a duplicate key).
 */
internal object StatsKeys {
    const val HEADER = "header"

    fun title(kind: String) = "title:$kind"

    fun item(kind: String, key: String) = "item:$kind:$key"
}

private fun LazyListScope.ranking(kind: String, title: Int, items: List<RankedItem>, round: Boolean) {
    if (items.isEmpty()) return
    item(key = StatsKeys.title(kind)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
        )
    }
    itemsIndexed(items, key = { _, item -> StatsKeys.item(kind, item.key) }) { index, item -> RankedRow(index + 1, item, round) }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankedRow(place: Int, item: RankedItem, round: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val plays = pluralStringResource(R.plurals.stats_play_count, item.plays, LocaleFormats.integer(item.plays, locale))
    val detail = listOfNotNull(item.subtitle, plays, listeningTimeText(item.listenedMs, locale)).joinToString(" · ")
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                LocaleFormats.integer(place, locale),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Artwork(item.artworkUri, Modifier.size(48.dp), if (round) CircleShape else RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        InsetDivider(start = 116.dp)
    }
}

@Composable
private fun listeningTimeText(ms: Long, locale: Locale): String {
    val time = ListeningTime.of(ms)
    return if (time.hours > 0) {
        stringResource(R.string.stats_time_hours_minutes, LocaleFormats.integer(time.hours, locale), LocaleFormats.integer(time.minutes, locale))
    } else {
        stringResource(R.string.stats_time_minutes, LocaleFormats.integer(time.minutes, locale))
    }
}

/** A medium date in the phone's time zone, such as "4 Oct 2026". */
private fun dateText(ms: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
