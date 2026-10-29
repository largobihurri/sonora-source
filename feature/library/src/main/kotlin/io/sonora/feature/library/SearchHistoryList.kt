package io.sonora.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.icon.SonoraIcons

/**
 * "Recent Searches" with its "Clear", then one row per [entries] (most recent first): tapping a row searches for it
 * again ([onSearch]), its × forgets it ([onRemove]).
 */
internal fun LazyListScope.recentSearchItems(
    entries: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent-searches-header") { RecentSearchesHeader(onClear) }
    items(entries, key = { "recent:$it" }) { entry -> RecentSearchRow(entry, onSearch, onRemove) }
}

@Composable
private fun RecentSearchesHeader(onClear: () -> Unit) {
    val clearDescription = stringResource(R.string.search_recent_clear_description)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.search_recent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = clearDescription, onClick = onClear)
                .semantics { contentDescription = clearDescription }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.search_recent_clear), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecentSearchRow(entry: String, onSearch: (String) -> Unit, onRemove: (String) -> Unit) {
    val removeDescription = stringResource(R.string.search_recent_remove, entry)
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onSearch(entry) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 20.dp),
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = removeDescription) { onRemove(entry) }
                    .semantics { contentDescription = removeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(SonoraIcons.Clear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        InsetDivider(start = 20.dp)
    }
}
