package io.sonora.feature.settings.about

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.feature.settings.ActionRow
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup

/**
 * Settings › About: the app's name and version, the GPL statement with the source code and privacy policy links,
 * the not-affiliated notice, and Open-Source Licences, which lists the notices of the components this build carries
 * and opens each one with its full licence text. Back steps out one page at a time, then returns to Settings.
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AboutViewModel = hiltViewModel()) {
    var licences by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var linkFailed by remember { mutableStateOf(false) }
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            linkFailed = true
        } catch (_: IllegalArgumentException) {
            // Newer Compose versions report a link no app can open this way.
            linkFailed = true
        }
    }

    val notice = selected?.let { name -> notices?.firstOrNull { it.name == name } }
    when {
        notice != null -> LicenceDetail(notice, viewModel, onBack = { selected = null }, onOpen = open, modifier = modifier)
        licences -> LicenceList(
            notices = notices,
            onSelect = { selected = it.name },
            onBack = { licences = false },
            modifier = modifier,
        )
        else -> AboutMain(viewModel.app, onBack, onOpen = open, onLicences = { licences = true }, modifier = modifier)
    }

    if (linkFailed) {
        TranslucentAlert(
            title = stringResource(R.string.about_link_failed),
            onDismiss = { linkFailed = false },
            confirm = TranslucentAlertButton(stringResource(R.string.about_ok)) { linkFailed = false },
        )
    }
}

@Composable
private fun AboutMain(
    app: InstalledApp,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onLicences: () -> Unit,
    modifier: Modifier,
) {
    val strings = rememberStrings()
    BackHandler(onBack = onBack)
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_about))
        }
        item(key = "app") {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        AboutText.version(app.versionName, app.versionCode, strings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "links") {
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                ActionRow(SonoraIcons.Code, stringResource(R.string.about_source_code), onClick = { onOpen(AboutLinks.SOURCE_CODE) })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.Shield, stringResource(R.string.about_privacy_policy), onClick = { onOpen(AboutLinks.PRIVACY_POLICY) })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.Document, stringResource(R.string.about_licences), onClick = onLicences)
            }
            SectionFooter(stringResource(R.string.about_gpl))
            Spacer(Modifier.height(16.dp))
            SectionFooter(stringResource(R.string.about_not_affiliated))
        }
    }
}

@Composable
private fun LicenceList(
    notices: List<LicenceNotice>?,
    onSelect: (LicenceNotice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onBack)
    val (fullOnly, everywhere) = notices.orEmpty().partition { it.fullOnly }
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_about), onBack)
            LargeTitle(stringResource(R.string.about_licences))
        }
        if (everywhere.isNotEmpty()) {
            item(key = "notices") {
                SettingsGroup { NoticeRows(everywhere, onSelect) }
                SectionFooter(stringResource(R.string.about_licences_footer))
            }
        }
        if (fullOnly.isNotEmpty()) {
            item(key = "full") {
                SectionHeader(stringResource(R.string.about_licences_full_header))
                SettingsGroup { NoticeRows(fullOnly, onSelect) }
                SectionFooter(stringResource(R.string.about_licences_full_footer))
            }
        }
    }
}

@Composable
private fun NoticeRows(notices: List<LicenceNotice>, onSelect: (LicenceNotice) -> Unit) {
    notices.forEachIndexed { index, notice ->
        if (index > 0) InsetDivider(start = 16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(notice) }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(notice.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(notice.licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                SonoraIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** What the detail page knows of a notice's full licence text. */
private sealed interface FullText {
    data object Loading : FullText
    data object None : FullText
    data object Unreadable : FullText
    data class Loaded(val paragraphs: List<String>) : FullText
}

@Composable
private fun LicenceDetail(
    notice: LicenceNotice,
    viewModel: AboutViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onBack)
    val fullText by produceState<FullText>(FullText.Loading, notice.text) {
        val path = notice.text
        value = when (path) {
            null -> FullText.None
            else -> viewModel.licenceText(path)?.let { FullText.Loaded(AboutText.paragraphs(it)) } ?: FullText.Unreadable
        }
    }
    val noticeParagraphs = remember(notice.notice) { AboutText.paragraphs(notice.notice) }
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.about_licences), onBack)
            LargeTitle(notice.name)
        }
        item(key = "licence") {
            SettingsGroup {
                Text(
                    notice.licence,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                notice.url?.let { url ->
                    InsetDivider(start = 16.dp)
                    ActionRow(SonoraIcons.Link, stringResource(R.string.about_licence_website), onClick = { onOpen(url) })
                }
            }
        }
        if (noticeParagraphs.isNotEmpty()) {
            item(key = "notice") { Spacer(Modifier.height(8.dp)) }
            items(noticeParagraphs) { paragraph -> Paragraph(paragraph) }
        }
        when (val text = fullText) {
            FullText.Loading, FullText.None -> Unit
            FullText.Unreadable -> item(key = "unreadable") {
                SectionHeader(stringResource(R.string.about_licence_text_header))
                SectionFooter(stringResource(R.string.about_licence_text_unavailable))
            }
            is FullText.Loaded -> {
                item(key = "text-header") { SectionHeader(stringResource(R.string.about_licence_text_header)) }
                items(text.paragraphs) { paragraph -> Paragraph(paragraph, monospace = true) }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, monospace: Boolean = false) {
    val base = MaterialTheme.typography.bodySmall
    Text(
        text,
        style = if (monospace) base.copy(fontFamily = FontFamily.Monospace) else base,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp),
    )
}
