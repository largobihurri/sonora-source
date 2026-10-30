package io.sonora.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.theme.AccentResolver
import io.sonora.core.designsystem.theme.ColorMath
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ThemeMode
import io.sonora.feature.settings.about.AboutScreen
import io.sonora.feature.settings.backup.BackupScreen
import io.sonora.feature.settings.duplicates.DuplicatesScreen
import io.sonora.feature.settings.dj.DjModeSection
import io.sonora.feature.settings.dub.DubModeSection
import io.sonora.feature.settings.audio.AudioOutputSection
import io.sonora.feature.settings.audio.AudioOutputViewModel
import io.sonora.feature.settings.playback.PlaybackSection
import io.sonora.feature.settings.watch.WatchScreen

/**
 * iOS-style grouped settings list, pushed from a tab's top bar gear; the host applies the status bar inset.
 * Sections: Library Folders, Scanning, Wear OS watch (when the build has the watch link), Language, Appearance, Now Playing, Playback, Audio Output, DJ Mode, then the About row. Appearance
 * changes recolour the whole app at once, this screen included, which is the live preview. [openPage] (asked for from
 * outside Settings, e.g. by the duplicates notification) shows that page, then [onPageOpened]; back from a page returns to this list.
 * [backLabel] names the screen Settings goes back to (the tab it was opened from); the Library when null.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    openPage: SettingsPage? = null,
    onPageOpened: () -> Unit = {},
    backLabel: String? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    importViewModel: ImportArchiveViewModel = hiltViewModel(key = IMPORT_ARCHIVE_VIEW_MODEL_KEY),
    audioOutputViewModel: AudioOutputViewModel = hiltViewModel(),
) {
    // Starts on a page asked for as Settings opens, so the list never shows for a frame first.
    var page by rememberSaveable { mutableStateOf(openPage) }
    val latestPageOpened by rememberUpdatedState(onPageOpened)
    LaunchedEffect(openPage) {
        if (openPage != null) {
            page = openPage
            latestPageOpened()
        }
    }
    val closePage = { page = null }
    // Remembered before a page replaces the list, so coming back from it keeps the list where it was: the list's own
    // composition (and any state remembered there) leaves while the page shows.
    val listState = rememberLazyListState()
    when (page) {
        SettingsPage.HiddenItems -> return HiddenItemsScreen(onBack = closePage, modifier)
        SettingsPage.ImportHistory -> return ImportHistoryScreen(onBack = closePage, modifier)
        SettingsPage.Duplicates -> return DuplicatesScreen(onBack = closePage, modifier)
        SettingsPage.Backup -> return BackupScreen(onBack = closePage, modifier)
        SettingsPage.Watch -> return WatchScreen(onBack = closePage, modifier)
        SettingsPage.About -> return AboutScreen(onBack = closePage, modifier)
        null -> Unit
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val hideAlbumsWithoutArtwork by viewModel.hideAlbumsWithoutArtwork.collectAsStateWithLifecycle()
    val externalControl by viewModel.externalControl.collectAsStateWithLifecycle()
    val audioOutput by audioOutputViewModel.state.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    var removing by remember { mutableStateOf<LibraryFolder?>(null) }
    val pickFolder = rememberFolderPicker(onPicked = viewModel::addFolder)
    val pickArchive = rememberArchivePicker(importViewModel)

    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            BackHeader(backLabel ?: stringResource(R.string.settings_back_library), onBack)
            LargeTitle(stringResource(R.string.settings_title))
        }
        item {
            SectionHeader(stringResource(R.string.settings_folders_header))
            SettingsGroup {
                for (folder in state.folders) {
                    FolderRow(folder, onRemove = { removing = folder })
                    InsetDivider(start = 52.dp)
                }
                ActionRow(SonoraIcons.Add, stringResource(R.string.settings_add_folder), onClick = { if (!pickFolder()) viewModel.pickerMissing() })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.Archive, stringResource(R.string.settings_import_archive), onClick = { if (!pickArchive()) viewModel.pickerMissing() })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.History, stringResource(R.string.settings_import_history), onClick = { page = SettingsPage.ImportHistory })
            }
            SectionFooter(stringResource(R.string.settings_folders_footer))
        }
        item {
            SectionHeader(stringResource(R.string.settings_scanning_header))
            SettingsGroup {
                ActionRow(SonoraIcons.Refresh, stringResource(R.string.settings_rescan), onClick = viewModel::rescan, enabled = state.folders.isNotEmpty())
                ScanStatus(state.progress)
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                ActionRow(SonoraIcons.Hidden, stringResource(R.string.settings_hidden_items), onClick = { page = SettingsPage.HiddenItems })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.Copy, stringResource(R.string.settings_duplicates), onClick = { page = SettingsPage.Duplicates })
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.SaveTo, stringResource(R.string.settings_backup), onClick = { page = SettingsPage.Backup })
            }
        }
        if (viewModel.watchAvailable) {
            item {
                SectionHeader(stringResource(R.string.settings_watch_header))
                SettingsGroup {
                    ActionRow(SonoraIcons.Watch, stringResource(R.string.settings_watch), onClick = { page = SettingsPage.Watch })
                }
                SectionFooter(stringResource(R.string.settings_watch_footer))
            }
        }
        language?.let { current ->
            item {
                SectionHeader(stringResource(R.string.settings_language_header))
                SettingsGroup {
                    AppLanguage.entries.forEachIndexed { index, option ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        ChoiceRow(
                            title = SettingsText.languageName(option, strings),
                            subtitle = null,
                            selected = current == option,
                            onSelect = { viewModel.setLanguage(option) },
                            titleLanguage = option.tag,
                        )
                    }
                }
                // The footer explains the default; once another language is chosen it no longer applies.
                if (current == AppLanguage.SPANISH) SectionFooter(stringResource(R.string.settings_language_footer))
            }
        }
        appearance?.let { look ->
            item {
                SectionHeader(stringResource(R.string.settings_appearance_header))
                SettingsGroup { ThemePicker(look.theme, viewModel::setTheme, strings) }
                Spacer(Modifier.height(16.dp))
                SettingsGroup { SwitchRow(stringResource(R.string.settings_translucency), look.translucency, viewModel::setTranslucency) }
                val translucent = LocalTranslucentPolicy.current
                SectionFooter(
                    SettingsText.translucencyFooter(
                        Build.VERSION.SDK_INT,
                        translucent.highContrast,
                        strings,
                        limitedTo = translucent.tier.takeIf { translucent.limited },
                    ),
                )
                SectionHeader(stringResource(R.string.settings_accent_header))
                SettingsGroup {
                    AccentMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        ChoiceRow(
                            title = SettingsText.accentModeTitle(mode, strings),
                            subtitle = SettingsText.accentModeSubtitle(mode, Build.VERSION.SDK_INT, strings),
                            selected = look.accentMode == mode,
                            onSelect = { viewModel.setAccentMode(mode) },
                        )
                    }
                    if (look.accentMode == AccentMode.PRESET) {
                        InsetDivider(start = 16.dp)
                        PresetPalette(look.preset, viewModel::setAccentPreset, strings)
                    }
                }
                SectionFooter(stringResource(R.string.settings_accent_footer))
            }
        }
        nowPlaying?.let { buttons ->
            item {
                SectionHeader(stringResource(R.string.settings_now_playing_header))
                SettingsGroup {
                    SwitchRow(stringResource(R.string.settings_show_lyrics_button), buttons.showLyricsButton, viewModel::setShowLyricsButton)
                    InsetDivider(start = 16.dp)
                    SwitchRow(stringResource(R.string.settings_show_file_info_button), buttons.showFileInfoButton, viewModel::setShowFileInfoButton)
                }
                SectionFooter(stringResource(R.string.settings_now_playing_footer))
                SectionHeader(stringResource(R.string.settings_rotation_header))
                SettingsGroup {
                    NowPlayingRotation.entries.forEachIndexed { index, option ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        ChoiceRow(
                            title = stringResource(
                                when (option) {
                                    NowPlayingRotation.NOW_PLAYING_LANDSCAPE -> R.string.settings_rotation_now_playing
                                    NowPlayingRotation.ALBUM_VISUAL_COLLECTION -> R.string.settings_rotation_visual_collection
                                },
                            ),
                            subtitle = null,
                            selected = buttons.rotation == option,
                            onSelect = { viewModel.setNowPlayingRotation(option) },
                        )
                    }
                }
                SectionFooter(stringResource(R.string.settings_rotation_footer))
                SectionHeader(stringResource(R.string.settings_gravity_header))
                SettingsGroup {
                    SwitchRow(stringResource(R.string.settings_gravity), buttons.gravity, viewModel::setGravity)
                }
                SectionFooter(stringResource(R.string.settings_gravity_footer))
            }
        }
        hideAlbumsWithoutArtwork?.let { hide ->
            item {
                SectionHeader(stringResource(R.string.settings_visual_collection_header))
                SettingsGroup {
                    SwitchRow(stringResource(R.string.settings_hide_albums_without_artwork), hide, viewModel::setHideAlbumsWithoutArtwork)
                }
                SectionFooter(stringResource(R.string.settings_hide_albums_without_artwork_footer))
            }
        }
        item { PlaybackSection(locked = audioOutput?.armed == true) }
        item { AudioOutputSection(audioOutputViewModel) }
        item { DjModeSection() }
        item { DubModeSection() }
        externalControl?.let { enabled ->
            item {
                SectionHeader(stringResource(R.string.settings_external_control_header))
                SettingsGroup {
                    SwitchRow(stringResource(R.string.settings_external_control), enabled, viewModel::setExternalControl)
                }
                SectionFooter(stringResource(R.string.settings_external_control_footer))
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            SettingsGroup {
                ActionRow(SonoraIcons.Info, stringResource(R.string.settings_about), onClick = { page = SettingsPage.About })
            }
        }
    }

    removing?.let { folder ->
        TranslucentAlert(
            title = stringResource(R.string.settings_remove_folder_title, folder.displayName),
            message = stringResource(R.string.settings_remove_folder_message),
            onDismiss = { removing = null },
            confirm = TranslucentAlertButton(stringResource(R.string.settings_remove), destructive = true) {
                viewModel.removeFolder(folder)
                removing = null
            },
            cancel = TranslucentAlertButton(stringResource(R.string.settings_cancel)) { removing = null },
        )
    }
    notice?.let {
        val text = SettingsText.notice(it, strings)
        TranslucentAlert(
            title = text.title,
            message = text.message,
            onDismiss = viewModel::dismissNotice,
            confirm = TranslucentAlertButton(stringResource(R.string.settings_ok), onClick = viewModel::dismissNotice),
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp),
    )
}

/** Inset grouped card holding a section's rows. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

@Composable
private fun FolderRow(folder: LibraryFolder, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(SonoraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            folder.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(SonoraIcons.RemoveCircle, contentDescription = stringResource(R.string.settings_remove_folder, folder.displayName), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** A row with a trailing iOS-style switch; the whole row toggles it. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null, // the row handles the toggle, so the switch and the label act as one control
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun ScanStatus(progress: ScanProgress) {
    val status = SettingsText.scanStatus(progress, rememberStrings()) ?: return
    InsetDivider(start = 16.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (progress is ScanProgress.Scanning) {
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.scanned.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** iOS-style segmented control choosing System, Light or Dark. */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, strings: Strings) {
    SegmentedControl(
        options = ThemeMode.entries,
        selected = selected,
        label = { SettingsText.themeLabel(it, strings) },
        onSelect = onSelect,
        modifier = Modifier.padding(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * A row of a single-choice list: a trailing checkmark marks the chosen one; while not [enabled] it is faded and does
 * nothing. [titleLanguage] (a language tag) makes
 * TalkBack read a title written in another language, such as a language's own name, with that language's voice.
 */
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    titleLanguage: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val text = titleLanguage?.let { tag ->
                buildAnnotatedString { withStyle(SpanStyle(localeList = LocaleList(tag))) { append(title) } }
            } ?: AnnotatedString(title)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(SonoraIcons.Checkmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
    }
}

/** How faded a row looks while it cannot be changed. */
internal const val DISABLED_ALPHA = 0.4f

/** The preset colours as circles, as they look in the current theme; the chosen one carries a checkmark. */
@Composable
private fun PresetPalette(selected: AccentPreset, onSelect: (AccentPreset) -> Unit, strings: Strings) {
    val dark = SonoraTheme.isDark
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (preset in AccentPreset.entries) {
            val argb = AccentResolver.preset(preset).forTheme(dark)
            val isSelected = preset == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(preset) })
                    .semantics { contentDescription = SettingsText.presetName(preset, strings) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(SonoraIcons.Checkmark, contentDescription = null, tint = Color(ColorMath.contentOn(argb)), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
