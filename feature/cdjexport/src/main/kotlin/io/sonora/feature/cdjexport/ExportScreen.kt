package io.sonora.feature.cdjexport

import android.content.ClipData
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentBarHeight
import io.sonora.core.designsystem.component.TranslucentSettingsBar
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.layout.ContentWidthBox
import io.sonora.core.designsystem.layout.TopChromeSpace
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.feature.cdjexport.export.EjectState
import io.sonora.feature.cdjexport.export.openEjectScreen
import io.sonora.feature.cdjexport.export.volumeRootPickerIntent

/** The tab icon for this screen (Material "usb"), so the app shell needs no icon of its own. */
val ExportTabIcon: ImageVector by lazy { usbIcon() }

/**
 * "Export" tab: prepares a USB stick for Pioneer players and exports tracks with a Rekordbox library to it.
 * Back during a format is blocked with a hint (Cancel is the only way to stop it), and the screen stays on meanwhile.
 * Files, folders and the stick are picked with the system's document picker; a stick Sonora already has access to
 * opens without it.
 */
@Composable
fun ExportScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: PrepareUsbViewModel = hiltViewModel(),
    exportViewModel: ExportToStickViewModel = hiltViewModel(),
    ejectViewModel: StickEjectViewModel = hiltViewModel(),
    washViewModel: ExportWashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    val ejectState by ejectViewModel.state.collectAsStateWithLifecycle()
    val artwork by washViewModel.artwork.collectAsStateWithLifecycle()
    // The covers wanted: those of the tracks the list shows, the first of which tints the tab.
    val listed = remember(exportState.tracks) { exportState.tracks.take(MAX_LISTED).map(PickedTrack::uri) }
    LaunchedEffect(listed) { washViewModel.show(listed) }
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        exportViewModel.onFilesPicked(uris.map { it.toString() })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportViewModel.onFolderPicked(it.toString()) }
    }
    val pickStick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportViewModel.onStickPicked(it.toString()) }
    }
    // The picker already at a stick's root (StorageVolume.createOpenDocumentTreeIntent): one tap to use it.
    val pickStickAtRoot = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { exportViewModel.onStickPicked(it.toString()) }
    }
    val openStickPicker: (StickPick) -> Unit = { pick ->
        when (pick) {
            StickPick.Opened -> Unit
            is StickPick.PickerAt ->
                volumeRootPickerIntent(context, pick.uuid)?.let(pickStickAtRoot::launch) ?: pickStick.launch(null)
            is StickPick.Picker -> pickStick.launch(pick.initialUri?.toUri())
        }
    }
    // Android's storage screen for the eject, from this activity so Back returns to Sonora.
    LaunchedEffect(ejectState) {
        val opening = ejectState as? EjectState.OpenScreen ?: return@LaunchedEffect
        ejectViewModel.onScreenOpened(openEjectScreen(context, opening.screens, opening.target?.uuid))
    }
    val eject = EjectControls(
        state = ejectState,
        onEject = {
            // Checked again on the latest state: an export or format may have started since the button was drawn.
            if (ejectPlace(viewModel.state.value.step, exportViewModel.state.value.step) != EjectPlace.NONE) {
                // Sonora lets go of the stick first: the USB connection of a format and the export's stick (also one
                // still opening), which is the one to eject.
                viewModel.releaseStick()
                val chosen = exportViewModel.forgetStick()
                ejectViewModel.eject(chosen)
            }
        },
        onDismiss = ejectViewModel::dismiss,
    )
    // Leaving a result leaves its eject too.
    val prepareBack = {
        ejectViewModel.dismiss()
        viewModel.back()
    }
    val step = state.step
    KeepScreenOn(enabled = step is PrepareStep.Formatting)
    BackHandler(
        enabled = step is PrepareStep.Ready || step is PrepareStep.PermissionDenied ||
            step is PrepareStep.Finished || step is PrepareStep.Formatting,
        onBack = prepareBack,
    )
    ExportContent(
        state = state,
        actions = PrepareUsbActions(
            onSelectDevice = viewModel::selectDevice,
            onRetryPermission = viewModel::retryPermission,
            onBack = prepareBack,
            onDismissNotice = viewModel::dismissNotice,
            onLabelChange = viewModel::onLabelChange,
            onRequestFormat = viewModel::requestFormat,
            onConfirmationChange = viewModel::onConfirmationChange,
            onDismissConfirmation = viewModel::dismissConfirmation,
            onConfirmFormat = viewModel::confirmFormat,
            onCancelFormat = viewModel::cancelFormat,
            onCopyDetails = { details -> copyToClipboard(context, details) },
        ),
        exportState = exportState,
        eject = eject,
        artwork = artwork,
        exportActions = ExportToStickActions(
            onAddFiles = { pickFiles.launch(arrayOf("audio/*")) },
            onAddFolder = { pickFolder.launch(null) },
            onRemoveTrack = exportViewModel::removeTrack,
            onClearTracks = exportViewModel::clearTracks,
            onPickStick = { openStickPicker(exportViewModel.chooseStick()) },
            onPickVolume = { uuid -> openStickPicker(exportViewModel.chooseVolume(uuid)) },
            onPlaylistNameChange = exportViewModel::onPlaylistNameChange,
            onExport = exportViewModel::startExport,
            onConfirmReplace = exportViewModel::confirmReplace,
            onDismissReplace = exportViewModel::dismissReplace,
            onCancel = exportViewModel::cancelExport,
            onDone = {
                ejectViewModel.dismiss()
                exportViewModel.finish()
            },
            onCopyDetails = { details -> copyToClipboard(context, details) },
        ),
        modifier = modifier,
        onOpenSettings = onOpenSettings?.takeIf { settingsGearShown(step) },
    )
}

@Composable
internal fun ExportContent(
    state: PrepareUsbState,
    actions: PrepareUsbActions,
    exportState: ExportToStickState,
    exportActions: ExportToStickActions,
    eject: EjectControls,
    modifier: Modifier = Modifier,
    /** The library's artwork of the listed tracks, by track URI; the first listed track tints the tab. */
    artwork: Map<String, String> = emptyMap(),
    /** Opens Settings from the gear floating at the top, as the Library's does; no gear when null. */
    onOpenSettings: (() -> Unit)? = null,
) {
    // One eject at a time on screen, and none while a stick is being written to.
    val place = ejectPlace(state.step, exportState.step)
    // One backdrop for the screen: the wash below records it, the translucent panels above sample it.
    val translucent = rememberTranslucentBackdrop()
    val washCover = ExportWash.sourceUri(exportState.tracks)?.let(artwork::get)
    Box(modifier.fillMaxSize()) {
        ExportWashLayer(washCover, translucent, Modifier.fillMaxSize())
        // No Surface sits behind this screen: without this, text without its own colour would be black on dark themes.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalTranslucentBackdrop provides translucent,
        ) {
            // On a large screen the sections keep a list's width, centred; the page still scrolls across the whole width.
            ContentWidthBox(Modifier.fillMaxSize()) { area ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = area.inset)
                        .padding(bottom = BottomChromePadding),
                ) {
                    // Clear of the floating gear's bar, or of the shell's top pill when that reaches lower.
                    TopChromeSpace(own = TranslucentBarHeight)
                    LargeTitle(stringResource(R.string.cdj_title))
                    PrepareUsbSection(state, actions, eject.takeIf { place == EjectPlace.FORMAT })
                    Spacer(Modifier.height(32.dp))
                    ExportToStickSection(exportState, exportActions, eject.takeIf { place == EjectPlace.EXPORT }, artwork)
                }
            }
            if (onOpenSettings != null) TranslucentSettingsBar(onOpenSettings, Modifier.windowInsetsPadding(WindowInsets.statusBars))
        }
    }
}

/**
 * Whether the tab's Settings gear shows at [step]: not while a stick is being formatted, as Settings would cover this
 * screen, which keeps the display on and holds Back until the format ends.
 */
internal fun settingsGearShown(step: PrepareStep): Boolean = step !is PrepareStep.Formatting

/** Puts a technical report on the clipboard; Android 13+ confirms the copy itself. */
private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Sonora technical details", text))
}

/** Sets FLAG_KEEP_SCREEN_ON on the window (through the root view) while [enabled] and this screen is shown. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) view.keepScreenOn = true
        onDispose { if (enabled) view.keepScreenOn = false }
    }
}
