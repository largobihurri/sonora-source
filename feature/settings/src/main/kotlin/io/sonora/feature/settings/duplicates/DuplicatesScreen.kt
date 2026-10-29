package io.sonora.feature.settings.duplicates

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.feature.settings.ActionRow
import io.sonora.feature.settings.ImportPanelAction
import io.sonora.feature.settings.ImportPanelTitle
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SettingsGroup
import io.sonora.feature.settings.SwitchRow
import java.time.ZoneId

/**
 * Settings › Duplicates: the notification switch, the scan's progress with "Look for Duplicates Again", and each group
 * of copies (Back returns to Settings). A group opens as a floating translucent panel comparing its copies side by
 * side, the values that differ highlighted and the suggested copy marked; the selected copies are deleted after an
 * irreversible confirmation (a read-only folder is picked again to allow it), or the group is marked "Not a Duplicate".
 * Notifications are asked for (Android 13+) only here: on opening with the switch on, or on turning it on.
 */
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // A store of the page's own (kept across rotation): closing the page drops the group opened, its selection and any
    // dialog, so the page opens afresh from the notification or the notice. The scan's groups live on in [DuplicateScan].
    viewModel: DuplicatesViewModel = hiltViewModel(rememberViewModelStoreOwner()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val deletion by viewModel.deletion.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    val askNotifications = rememberNotificationAsk()
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { if (viewModel.notificationsWanted()) askNotifications() }

    val groups = (state as? DuplicateScanState.Found)?.groups.orEmpty()
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_duplicates))
            Text(
                stringResource(R.string.duplicates_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                SwitchRow(stringResource(R.string.duplicates_notify), notifications) { on ->
                    viewModel.setNotifications(on)
                    if (on) askNotifications()
                }
            }
            SectionFooter(stringResource(R.string.duplicates_notify_footer))
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                ActionRow(
                    SonoraIcons.Refresh,
                    stringResource(R.string.duplicates_scan_again),
                    onClick = viewModel::scanAgain,
                    enabled = state !is DuplicateScanState.Scanning,
                )
                ScanStatus(state, DuplicatesText.status(state, strings))
            }
            Spacer(Modifier.height(16.dp))
        }
        items(groups, key = { it.key }) { group ->
            GroupRow(group, DuplicatesText.line(group, strings), onOpen = { viewModel.open(group) })
        }
    }

    opened?.let { shown -> ComparisonPanel(shown, viewModel) }
    DeletionDialogs(deletion, viewModel)
    notice?.let { shown ->
        val (title, message) = DuplicatesText.notice(shown, strings)
        TranslucentAlert(
            title = title,
            message = message,
            onDismiss = viewModel::dismissNotice,
            confirm = TranslucentAlertButton(stringResource(R.string.duplicates_ok), onClick = viewModel::dismissNotice),
        )
    }
}

/** Asks for POST_NOTIFICATIONS on Android 13+ once per visit, and only when it was not granted. */
@Composable
private fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked && !context.notificationsGranted()) {
            asked = true
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: ActivityNotFoundException) {
                // No permission controller: the notification simply stays off.
            }
        }
    }
}

private fun Context.notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ScanStatus(state: DuplicateScanState, status: String?) {
    status ?: return
    InsetDivider(start = 16.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (state is DuplicateScanState.Scanning) {
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.checked.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupRow(group: DuplicateGroup, line: String, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val song = group.suggested
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${song.artist} · $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        InsetDivider(start = 20.dp)
    }
}

/**
 * One group as a translucent panel: its copies side by side (a column each, best first) beside the fixed labels, each
 * with its "Delete this copy" box; copies that do not fit scroll sideways a whole column at a time
 * ([ComparisonColumns]). Values that differ between the copies are highlighted in the accent colour.
 */
@Composable
private fun ComparisonPanel(shown: OpenedGroup, viewModel: DuplicatesViewModel) {
    val strings = rememberStrings()
    val group = shown.group
    val rows = DuplicatesText.comparison(group, ZoneId.systemDefault(), strings)
    TranslucentPanel(group.suggested.title, onDismiss = viewModel::close) {
        Column {
            ImportPanelTitle(group.suggested.title, "${group.suggested.artist} · ${DuplicatesText.line(group, strings)}")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
                ComparisonTable(shown, rows, onToggle = viewModel::toggle)
                Text(
                    stringResource(R.string.duplicates_differences_note) + " " + stringResource(R.string.duplicates_keep_one),
                    color = LocalTranslucentContentColors.current.label.copy(alpha = SECONDARY_ALPHA),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                )
                TranslucentPanelGroup(title = null, footer = stringResource(R.string.duplicates_not_duplicate_note)) {
                    if (shown.selected.isNotEmpty()) {
                        ImportPanelAction(
                            pluralStringResource(R.plurals.duplicates_delete_selected, shown.selected.size, shown.selected.size),
                            SonoraIcons.Trash,
                            onClick = viewModel::chooseDelete,
                            destructive = true,
                        )
                        TranslucentPanelDivider()
                    }
                    ImportPanelAction(stringResource(R.string.duplicates_not_duplicate), SonoraIcons.Visible, onClick = viewModel::notADuplicate)
                }
            }
        }
    }
}

@Composable
private fun ComparisonTable(shown: OpenedGroup, rows: List<ComparisonRow>, onToggle: (String) -> Unit) {
    val translucent = LocalTranslucentContentColors.current
    val copies = shown.group.copies
    val unselected = copies.count { it.uri !in shown.selected }
    val scroll = rememberScrollState()
    // One row per field, so a value that wraps keeps its label and the other copies' values level with it. The labels
    // stay put; the copies' part of every row scrolls sideways together (one shared state) and settles on a column
    // edge, so no copy is left cut at either side.
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(translucent.label.copy(alpha = 0.06f)),
    ) {
        val columnWidth = ComparisonColumns.width(
            available = (maxWidth - LABEL_WIDTH).value,
            copies = copies.size,
            min = MIN_COLUMN_WIDTH.value,
            max = COLUMN_WIDTH.value,
        ).dp
        val fling = rememberColumnSnap(scroll, with(LocalDensity.current) { columnWidth.toPx() })
        val labelStyle = rememberLabelStyle(rows.map { it.label })
        // Every row's own overscroll would stretch that row alone, out of line with the others.
        val copiesScroll = Modifier.horizontalScroll(scroll, overscrollEffect = null, flingBehavior = fling)
        Column(Modifier.padding(vertical = 10.dp)) {
            Row {
                Spacer(Modifier.width(LABEL_WIDTH))
                Row(Modifier.weight(1f).then(copiesScroll)) {
                    copies.forEachIndexed { index, copy ->
                        val selected = copy.uri in shown.selected
                        // The last copy left unselected cannot be selected: one copy always stays.
                        val canToggle = selected || unselected > 1
                        Column(Modifier.width(columnWidth).padding(horizontal = 6.dp)) {
                            Text(stringResource(R.string.duplicates_copy, index + 1), color = translucent.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (index == 0) {
                                Text(stringResource(R.string.duplicates_suggested), color = translucent.accent, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(
                                Modifier.toggleable(value = selected, enabled = canToggle, role = Role.Checkbox, onValueChange = { onToggle(copy.uri) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null,
                                    enabled = canToggle,
                                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error),
                                )
                                Text(
                                    stringResource(R.string.duplicates_delete_this),
                                    color = if (selected) MaterialTheme.colorScheme.error else translucent.label.copy(alpha = SECONDARY_ALPHA),
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            rows.forEach { row ->
                Row(Modifier.heightIn(min = ROW_HEIGHT).padding(vertical = 3.dp)) {
                    Text(
                        row.label,
                        color = translucent.label.copy(alpha = SECONDARY_ALPHA),
                        style = labelStyle,
                        maxLines = LABEL_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(LABEL_WIDTH).padding(start = LABEL_START_PADDING, end = LABEL_END_PADDING),
                    )
                    Row(Modifier.weight(1f).then(copiesScroll)) {
                        row.values.forEach { value ->
                            Text(
                                value,
                                color = if (row.differs) translucent.accent else translucent.label,
                                fontWeight = if (row.differs) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 17.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(columnWidth).padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The field labels' style: one size for the whole column, the largest from [LABEL_MAX_SIZE] down to [LABEL_MIN_SIZE]
 * at which every label of [labels] wraps only between words within [LABEL_MAX_LINES] lines ([ComparisonLabels]). At
 * 13 sp "Profundidad" is wider than the 62 dp the column leaves on a 320 dp phone and was split "Profundida" / "d de
 * bits", and "Frecuencia de muestreo" needed a third line and was cut.
 */
@Composable
private fun rememberLabelStyle(labels: List<String>): TextStyle {
    val base = LocalTextStyle.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(labels, base, density, measurer) {
        val width = with(density) { (LABEL_WIDTH - LABEL_START_PADDING - LABEL_END_PADDING).roundToPx() }
        fun style(size: Float) = base.copy(fontSize = size.sp, lineHeight = LABEL_LINE_HEIGHT)
        val size = ComparisonLabels.fontSize(LABEL_MAX_SIZE.value, LABEL_MIN_SIZE.value, LABEL_SIZE_STEP) { size ->
            ComparisonLabels.worst(
                labels.map { label ->
                    val layout = measurer.measure(
                        label,
                        style(size),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = LABEL_MAX_LINES,
                        constraints = Constraints(maxWidth = width),
                    )
                    val last = layout.lineCount - 1
                    when {
                        layout.didOverflowWidth || layout.didOverflowHeight || (last >= 0 && layout.isLineEllipsized(last)) ->
                            ComparisonLabels.Fit.Cut
                        ComparisonLabels.wrapsBetweenWords(label, List(layout.lineCount) { layout.getLineStart(it) }) ->
                            ComparisonLabels.Fit.Fits
                        else -> ComparisonLabels.Fit.SplitsWord
                    }
                },
            )
        }
        style(size)
    }
}

/** Settles a sideways scroll of [column] wide columns on a column edge ([ComparisonColumns.snapDelta]). */
@Composable
private fun rememberColumnSnap(scroll: ScrollState, column: Float): FlingBehavior {
    val edges = remember(scroll, column) {
        object : SnapLayoutInfoProvider {
            override fun calculateSnapOffset(velocity: Float): Float =
                ComparisonColumns.snapDelta(scroll.value.toFloat(), column, scroll.maxValue.toFloat(), velocity)
        }
    }
    return rememberSnapFlingBehavior(edges)
}

@Composable
private fun DeletionDialogs(deletion: CopiesDeletion?, viewModel: DuplicatesViewModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.accessPicked(uri.toString()) else viewModel.cancelDelete()
    }
    when (deletion) {
        null, CopiesDeletion.Deleting -> Unit
        is CopiesDeletion.Confirm -> TranslucentAlert(
            title = pluralStringResource(R.plurals.duplicates_delete_title, deletion.selected.size, deletion.selected.size),
            message = stringResource(R.string.duplicates_delete_message),
            onDismiss = viewModel::cancelDelete,
            confirm = TranslucentAlertButton(stringResource(R.string.duplicates_delete_confirm), destructive = true, onClick = viewModel::confirmDelete),
            cancel = TranslucentAlertButton(stringResource(R.string.duplicates_cancel), onClick = viewModel::cancelDelete),
        )
        is CopiesDeletion.NeedsAccess -> {
            val message = if (deletion.wrongFolder) R.string.duplicates_access_wrong_folder else R.string.duplicates_access_message
            TranslucentAlert(
                title = stringResource(R.string.duplicates_access_title),
                message = stringResource(message, deletion.request.folderName),
                onDismiss = viewModel::cancelDelete,
                confirm = TranslucentAlertButton(stringResource(R.string.duplicates_access_choose)) {
                    try {
                        pickFolder.launch(deletion.request.pickerStartUri.toUri())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.cancelDelete()
                    }
                },
                cancel = TranslucentAlertButton(stringResource(R.string.duplicates_cancel), onClick = viewModel::cancelDelete),
            )
        }
    }
}

private const val SECONDARY_ALPHA = 0.62f
private val LABEL_WIDTH = 80.dp
private val LABEL_START_PADDING = 12.dp
private val LABEL_END_PADDING = 6.dp
private val LABEL_MAX_SIZE = 13.sp
private val LABEL_MIN_SIZE = 11.sp
private const val LABEL_SIZE_STEP = 0.5f
private val LABEL_LINE_HEIGHT = 1.3.em
private const val LABEL_MAX_LINES = 3

/** A copy's column: as wide as the room allows up to [COLUMN_WIDTH], never under [MIN_COLUMN_WIDTH] unless the room is. */
private val COLUMN_WIDTH = 132.dp
private val MIN_COLUMN_WIDTH = 88.dp
private val ROW_HEIGHT = 26.dp
