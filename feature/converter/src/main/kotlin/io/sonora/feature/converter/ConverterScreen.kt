package io.sonora.feature.converter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.ConfirmationBanner
import io.sonora.core.designsystem.component.TranslucentSettingsBar
import io.sonora.core.designsystem.component.rememberBannerState
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.LocalTopChromeInset
import io.sonora.core.designsystem.motion.PredictiveBackStack
import io.sonora.feature.converter.ui.BatchDetailContent
import io.sonora.feature.converter.ui.ConverterMainActions
import io.sonora.feature.converter.ui.ConverterMainContent
import io.sonora.feature.converter.ui.DeleteFromHistoryDialog
import io.sonora.feature.converter.ui.FolderContent
import io.sonora.feature.converter.ui.InheritedFrom
import io.sonora.feature.converter.ui.LossyOverwriteDialog
import io.sonora.feature.converter.ui.OutputFormatContent
import io.sonora.feature.converter.ui.HistoryContent
import io.sonora.feature.converter.ui.NoticeDialog
import io.sonora.feature.converter.ui.ReplaceOriginalsDialog
import io.sonora.feature.converter.ui.rememberConverterPickers
import kotlinx.coroutines.flow.first

/** Key of the sources section in the main screen's list ([ConverterMainContent]). */
private const val SOURCES_KEY = "sources"

private val ConverterStackSaver = listSaver<SnapshotStateList<ConverterDest>, String>(
    save = { encodeConverterStack(it) },
    restore = { decodeConverterStack(it).toMutableStateList() },
)

/**
 * The Converter tab with its own push/pop stack: the converter, its history, a batch's detail and "Add Music…".
 * [libraryPicker] renders the library picker (another feature) for "Add Music…"; it adds the songs to the shared
 * pending sources itself and calls its `onDone` to come back. [openBatchId] (from a conversion notification) shows
 * that batch on the converter, then [onBatchOpened] clears the request. [showSources] (songs sent from the Library's
 * "Convert…") brings the converter's sources into view, then [onSourcesShown]. [backEnabled] lets the shell keep back
 * for an overlay (Now Playing) shown above the tab. The main screen's gear opens Settings ([onOpenSettings]), when given.
 */
@Composable
fun ConverterScreen(
    modifier: Modifier = Modifier,
    libraryPicker: @Composable (onDone: () -> Unit) -> Unit = {},
    openBatchId: Long? = null,
    onBatchOpened: () -> Unit = {},
    showSources: Boolean = false,
    onSourcesShown: () -> Unit = {},
    backEnabled: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: ConverterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val reveal by viewModel.revealRequested.collectAsStateWithLifecycle()
    val missingFiles by viewModel.missingFiles.collectAsStateWithLifecycle()
    val banner = rememberBannerState()
    val stack = rememberSaveable(saver = ConverterStackSaver) { listOf<ConverterDest>(ConverterDest.Main).toMutableStateList() }
    val push: (ConverterDest) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    val pickers = rememberConverterPickers(onSources = viewModel::addSources, onOutputFolder = viewModel::setOutputFolder)
    val convert = rememberNotificationPermissionGate(onReady = viewModel::convert)
    fun launch(picker: () -> Boolean) {
        if (!picker()) viewModel.pickerMissing()
    }
    val actions = ConverterMainActions(
        onOpenBatchOutput = { push(ConverterDest.Output(null, null)) },
        onOpenSource = { source ->
            push(if (source.isFolder) ConverterDest.Folder(source.pick.uri) else ConverterDest.Output(source.pick.uri, null))
        },
        onChooseOutputFolder = { launch(pickers.chooseOutputFolder) },
        onAddMusic = { push(ConverterDest.LibraryPicker) },
        onBrowseFiles = { launch(pickers.addFiles) },
        onBrowseFolder = { launch(pickers.addFolder) },
        onRemoveSource = viewModel::removeSource,
        onSelectMode = viewModel::setMode,
        onOverwriteChange = viewModel::setOverwriteOriginals,
        onConvert = convert,
        onCancelBatch = viewModel::cancelActiveBatch,
        onReplaceOriginals = viewModel::requestReplaceOriginals,
        onShowBatch = { push(ConverterDest.Batch(it)) },
        onPlayConverted = viewModel::playAll,
        onDismissBatch = viewModel::dismissActiveBatch,
        onOpenHistory = { push(ConverterDest.History) },
    )

    LaunchedEffect(openBatchId) {
        if (openBatchFromNotification(openBatchId, stack, viewModel::showBatch)) onBatchOpened()
    }
    // Songs sent from the Library scroll the sources into view once the main screen shows.
    val revealSources by viewModel.sourcesRevealRequested.collectAsStateWithLifecycle()
    LaunchedEffect(showSources) {
        if (showSourcesFromLibrary(showSources, stack)) {
            viewModel.revealSources()
            onSourcesShown()
        }
    }

    // Shown only while the tab is seen (started, nothing such as Now Playing over it), and confirmed once the banner
    // went away on its own: the tab hidden meanwhile cancels this, so the notice shows again when it is seen.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (missingFiles > 0 && backEnabled && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        val text = pluralStringResource(R.plurals.converter_missing_files, missingFiles, missingFiles)
        LaunchedEffect(missingFiles, text) {
            banner.show(text, SonoraIcons.Info)
            val shown = banner.message
            snapshotFlow { banner.message }.first { it != shown }
            viewModel.missingFilesShown(missingFiles)
        }
    }

    Box(modifier.fillMaxSize()) {
        PredictiveBackStack(stack, onPop = pop, modifier = Modifier.fillMaxSize(), backEnabled = backEnabled) { dest ->
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                when (dest) {
                    ConverterDest.Main -> {
                        val listState = rememberLazyListState()
                        // A batch that just started, or whose notification was opened (even if already shown), scrolls
                        // into view once; the request is handled first, so neither the screen coming back nor a restore
                        // after process death scrolls again.
                        LaunchedEffect(reveal) {
                            if (!reveal) return@LaunchedEffect
                            viewModel.revealHandled()
                            listState.animateScrollToItem(0)
                        }
                        LaunchedEffect(revealSources) {
                            if (!revealSources) return@LaunchedEffect
                            // Wait for the list to be laid out, scroll, and only then clear the request: clearing it first
                            // would restart this effect and cancel the scroll (a cancelled scroll is retried by the next screen).
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
                            listState.animateScrollToKey(SOURCES_KEY)
                            viewModel.sourcesRevealHandled()
                        }
                        // The gear floats over the list as the Library's does, its translucency sampling the list.
                        val translucent = rememberTranslucentBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().translucentBackdropSource(translucent).background(MaterialTheme.colorScheme.background)) {
                                ConverterMainContent(state, actions, listState = listState)
                            }
                            if (onOpenSettings != null) {
                                CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) { TranslucentSettingsBar(onOpenSettings) }
                            }
                        }
                    }
                    ConverterDest.LibraryPicker -> libraryPicker(pop)
                    is ConverterDest.Output -> OutputDestination(dest, state, viewModel, pop)
                    is ConverterDest.Folder -> {
                        LaunchedEffect(dest.uri) { viewModel.openFolder(dest.uri) }
                        val source = state.sources.firstOrNull { it.pick.uri == dest.uri }
                        if (source == null) {
                            // Gone from the batch (removed, or converted) while this screen was saved.
                            BackHeader(stringResource(R.string.converter_title), pop)
                        } else {
                            FolderContent(
                                name = source.displayName,
                                folderUri = dest.uri,
                                output = state.output,
                                contents = state.folders[dest.uri],
                                onBack = pop,
                                onOpenFolderFormat = { push(ConverterDest.Output(dest.uri, null)) },
                                onOpenSong = { push(ConverterDest.Output(dest.uri, it.uri)) },
                                onRetry = { viewModel.openFolder(dest.uri) },
                            )
                        }
                    }
                    ConverterDest.History -> HistoryContent(state.history, onBack = pop, onOpenBatch = { push(ConverterDest.Batch(it)) })
                    is ConverterDest.Batch -> {
                        val detail by remember(dest.batchId) { viewModel.batchDetail(dest.batchId) }.collectAsStateWithLifecycle(null)
                        val snapshot = detail
                        if (snapshot == null) {
                            BackHeader(stringResource(R.string.converter_history), pop)
                        } else {
                            BatchDetailContent(
                                snapshot,
                                onBack = pop,
                                onReplaceOriginals = { viewModel.requestReplaceOriginals(snapshot) },
                                onDelete = { deleting = dest.batchId },
                                onPlayAll = { viewModel.playAll(dest.batchId) },
                                onPlayItem = viewModel::playItem,
                            )
                        }
                    }
                }
            }
        }
        ConfirmationBanner(banner, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp + LocalTopChromeInset.current))
    }

    when (val shown = prompt) {
        null -> Unit
        is ReplacePrompt.LossyOverwrite ->
            LossyOverwriteDialog(shown.count, onConfirm = viewModel::confirmReplace, onDismiss = viewModel::dismissReplacePrompt)
        else -> ReplaceOriginalsDialog(shown, onConfirm = viewModel::confirmReplace, onDismiss = viewModel::dismissReplacePrompt)
    }
    notice?.let { NoticeDialog(it, onDismiss = viewModel::dismissNotice) }
    deleting?.let { batchId ->
        DeleteFromHistoryDialog(
            onConfirm = {
                deleting = null
                viewModel.deleteFromHistory(batchId)
                if (stack.lastOrNull() == ConverterDest.Batch(batchId)) pop()
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * The output picker of [dest]: the batch-wide target, a picked source's, or a song's inside a picked folder. A source
 * no longer waiting (removed, or converted, while the screen was saved) shows only the way back.
 */
@Composable
private fun OutputDestination(dest: ConverterDest.Output, state: ConverterUiState, viewModel: ConverterViewModel, pop: () -> Unit) {
    val output = state.output
    val converter = stringResource(R.string.converter_title)
    val sourceUri = dest.source
    if (sourceUri == null) {
        OutputFormatContent(stringResource(R.string.converter_profile_header), converter, output.batchWide, viewModel::setBatchWide, pop)
        return
    }
    val source = state.sources.firstOrNull { it.pick.uri == sourceUri }
    val child = dest.child
    val childName = child?.let { uri -> (state.folders[sourceUri] as? FolderContents.Listed)?.files?.firstOrNull { it.uri == uri }?.displayName }
    if (source == null || (child != null && childName == null)) {
        BackHeader(converter, pop)
        return
    }
    OutputFormatContent(
        title = childName ?: source.displayName,
        backLabel = if (child != null) source.displayName else converter,
        target = output.resolve(sourceUri, child),
        onSelect = { viewModel.setOutput(sourceUri, child, it) },
        onBack = pop,
        inherited = if (child != null) InheritedFrom.FOLDER else InheritedFrom.BATCH,
        inheritedTarget = if (child != null) output.resolve(sourceUri) else output.batchWide,
        manual = output.isManual(sourceUri, child),
        onUseInherited = { viewModel.useInherited(sourceUri, child) },
    )
}

/**
 * Scrolls to the item with [key], wherever the list's layout puts it. A lazy list only knows the keys of the items it
 * laid out, so when the item is not in view this starts from the top and moves on a screen at a time until it shows.
 * False when the list has no such item.
 */
private suspend fun LazyListState.animateScrollToKey(key: Any): Boolean {
    fun indexInView() = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
    indexInView()?.let { index ->
        animateScrollToItem(index)
        return true
    }
    animateScrollToItem(0)
    while (true) {
        indexInView()?.let { index ->
            animateScrollToItem(index)
            return true
        }
        val visible = layoutInfo.visibleItemsInfo
        val last = visible.lastOrNull()?.index ?: return false
        if (last >= layoutInfo.totalItemsCount - 1) return false
        // The last item in view becomes the first; an item taller than the screen still moves on by one.
        animateScrollToItem(maxOf(last, visible.first().index + 1))
    }
}

/**
 * Wraps [onReady] so that on Android 13+ the first call asks for POST_NOTIFICATIONS (the batch's progress lives in a
 * notification) when it is not granted, then runs [onReady] whatever the answer: a denied permission only hides the
 * notification, the conversion still runs.
 */
@Composable
private fun rememberNotificationPermissionGate(onReady: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onReady)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return { latest() }
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest() }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || asked) {
            latest()
        } else {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
