package io.sonora.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.library.marks.HiddenItem

/** Settings › Hidden Items: each item removed from Sonora with a Restore button; Back returns to Settings. */
@Composable
internal fun HiddenItemsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: HiddenItemsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_hidden_items))
            Text(
                stringResource(R.string.settings_hidden_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.settings_hidden_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                )
            }
        }
        items(items.size, key = { "${items[it].ref.kind}:${items[it].ref.key}" }) { index ->
            HiddenItemRow(items[index], onRestore = { viewModel.restore(items[index]) })
        }
    }
}

@Composable
private fun HiddenItemRow(item: HiddenItem, onRestore: () -> Unit) {
    val strings = rememberStrings()
    val restoreLabel = stringResource(R.string.settings_restore_item, item.title)
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    SettingsText.hiddenItemLine(item, strings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.semantics { contentDescription = restoreLabel }) {
                Text(stringResource(R.string.settings_restore))
            }
        }
        InsetDivider(start = 20.dp)
    }
}
