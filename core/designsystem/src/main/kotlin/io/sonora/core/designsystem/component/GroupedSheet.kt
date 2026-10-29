package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.R
import io.sonora.core.designsystem.translucent.TranslucentContentColors
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme
import kotlinx.coroutines.launch

/** A text button in a sheet's header, e.g. "Edit" or "Cancel". */
class HeaderAction(val label: String, val onClick: () -> Unit)

/**
 * iOS form sheet: a full-height modal with a centered [title] and a trailing "Done", over the grouped background;
 * [content] is usually a scrolling list of [InsetGroup]s. Done slides the sheet away before [onDismiss]. An optional
 * [leadingAction] (e.g. "Edit") sits at the start of the header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: HeaderAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpandBottomChromeBehindSheet()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    SonoraModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceVariant,
        contentColor = colors.onSurface,
        dragHandle = null,
    ) {
        TranslucentSheetHeader(
            title = title,
            leading = { leadingAction?.let { HeaderButton(it.label, bold = false, onClick = it.onClick) } },
            trailing = {
                HeaderButton(stringResource(R.string.ds_done), bold = true) {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }
            },
        )
        Column(content = content)
    }
}

/**
 * iOS edit sheet: [cancelLabel] at the start, a centered [title], and [confirmLabel] in bold at the end, dimmed while
 * [confirmEnabled] is false and replaced by a spinner while [busy]. While not [dismissible] (unsaved changes), swiping
 * the sheet away or tapping outside does nothing, so only Cancel ([onCancel]) closes it and the caller can confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    cancelLabel: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    dismissible: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpandBottomChromeBehindSheet()
    val canHide by rememberUpdatedState(dismissible && !busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || canHide },
    )
    val colors = MaterialTheme.colorScheme
    SonoraModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = colors.surfaceVariant,
        contentColor = colors.onSurface,
        dragHandle = null,
    ) {
        TranslucentSheetHeader(
            title = title,
            leading = { HeaderButton(cancelLabel, bold = false, enabled = !busy, onClick = onCancel) },
            trailing = {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.padding(horizontal = 16.dp).size(22.dp),
                        color = LocalTranslucentContentColors.current.accent,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    HeaderButton(confirmLabel, bold = true, enabled = confirmEnabled, onClick = onConfirm)
                }
            },
        )
        Column(content = content)
    }
}

/** The grabber's base under its gloss, in the translucent label colour, so it shows on light and dark surfaces. */
private const val GRABBER_BASE_ALPHA = 0.4f

/**
 * The translucent top of a sheet: the grabber over a centred [title] between optional [leading] and [trailing]
 * header buttons ([leading] and [trailing] read [LocalTranslucentContentColors]). The sheet's body under it stays
 * opaque. A Material sheet's window cannot sample the app, so the translucency is the flat tier there (as with
 * Translucency off); pass `dragHandle = null` to the [SonoraModalBottomSheet], since this draws the grabber.
 *
 * In the dark theme the header is the sheet itself (no translucency and no fill of its own, so whatever container the sheet
 * has shows through: `surfaceVariant` for a grouped sheet, `surface` for the playlist picker): the flat tier's white
 * rim outlined the full-width rectangle as a grey frame on the dark sheet, and a fill of its own showed as a band on a
 * sheet of another colour (W2). The labels keep the flat dark translucent colours.
 */
@Composable
fun TranslucentSheetHeader(title: String, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    val header = @Composable {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlossyGrabber(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .background(LocalTranslucentContentColors.current.label.copy(alpha = GRABBER_BASE_ALPHA), CapsuleShape),
            )
            SheetHeader(title, leading, trailing)
        }
    }
    if (SonoraTheme.isDark) {
        CompositionLocalProvider(LocalTranslucentContentColors provides flatDarkTranslucentColors()) {
            Box(Modifier.fillMaxWidth()) { header() }
        }
    } else {
        TranslucentSurface(Modifier.fillMaxWidth(), shape = RectangleShape, elevation = 0.dp, backdrop = null) { header() }
    }
}

/** The colours a flat dark [TranslucentSurface] gives its content, for a dark sheet header without translucency. */
@Composable
private fun flatDarkTranslucentColors(): TranslucentContentColors {
    val highContrast = LocalTranslucentPolicy.current.highContrast
    val accent = SonoraTheme.accentOnDark
    return remember(highContrast, accent) {
        val material = TranslucentMaterial.of(dark = true, tier = TranslucentTier.FLAT, highContrast = highContrast)
        TranslucentContentColors(Color(material.label), Color(material.contentColor(accent.toArgb())), TranslucentTier.FLAT)
    }
}

/** A sheet title's size, and the smallest it shrinks to before it ends in an ellipsis. */
private val SheetTitleSize = 17.sp
private val SheetTitleMinSize = 13.sp

/**
 * The header row: [leading] and [trailing] at the ends and the title centred clear of them, on one line. A title too
 * long for that room (e.g. "Editar información" on a 320 dp phone) shrinks, then ends in an ellipsis; it never wraps
 * onto a hidden second line, which cut it at a word with no ellipsis.
 */
@Composable
private fun SheetHeader(title: String, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        BasicText(
            title,
            style = TextStyle(
                color = LocalTranslucentContentColors.current.label,
                fontSize = SheetTitleSize,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = SheetTitleMinSize, maxFontSize = SheetTitleSize),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
private fun HeaderButton(label: String, bold: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val translucent = LocalTranslucentContentColors.current
    Text(
        label,
        fontSize = 17.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (enabled) translucent.accent else translucent.label.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** iOS inset grouped section: an uppercase [header], a rounded card of rows, and an optional [footer] note. */
@Composable
fun InsetGroup(header: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        header?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh),
            content = content,
        )
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/**
 * A label with its value trailing, as in iOS Settings > General > About. Long values wrap; [selectable] values can be
 * selected and copied with the system text toolbar.
 */
@Composable
fun ValueRow(label: String, value: String, selectable: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 17.sp, color = colors.onSurface, modifier = Modifier.padding(end = 16.dp))
        val valueText = @Composable {
            Text(value, fontSize = 17.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            if (selectable) SelectionContainer { valueText() } else valueText()
        }
    }
}
