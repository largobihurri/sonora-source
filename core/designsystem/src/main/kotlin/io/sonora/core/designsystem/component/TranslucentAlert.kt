package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme

/** The buttons a [TranslucentAlert] may show. */
enum class TranslucentAlertRole { Confirm, Extra, Cancel }

/** How an alert's buttons sit: side by side or [stacked], in [order] (leading or top first). */
internal data class TranslucentAlertButtonLayout(val stacked: Boolean, val order: List<TranslucentAlertRole>)

/**
 * The iOS alert button rule. One button fills the row. Two sit side by side, the cancel (or the extra action) leading
 * and the confirm trailing, when the [widestLabel] plus its [labelPadding] fits half the [rowWidth] less the [gap];
 * otherwise, and always with three, they stack with the actions first and Cancel last.
 */
internal fun translucentAlertButtonLayout(
    hasCancel: Boolean,
    hasExtra: Boolean,
    widestLabel: Float,
    rowWidth: Float,
    gap: Float,
    labelPadding: Float,
): TranslucentAlertButtonLayout {
    val actions = listOfNotNull(TranslucentAlertRole.Confirm, TranslucentAlertRole.Extra.takeIf { hasExtra })
    val all = actions + listOfNotNull(TranslucentAlertRole.Cancel.takeIf { hasCancel })
    return when {
        all.size == 1 -> TranslucentAlertButtonLayout(stacked = false, order = all)
        all.size == 2 && widestLabel + labelPadding <= (rowWidth - gap) / 2f -> TranslucentAlertButtonLayout(stacked = false, order = all.reversed())
        else -> TranslucentAlertButtonLayout(stacked = true, order = all)
    }
}

/** One button of a [TranslucentAlert]: [destructive] ones are red, disabled ones dimmed and inert. */
@Immutable
class TranslucentAlertButton(
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** About iOS's 270 pt alert, a little wider for Android's larger type. */
private val AlertWidth = 300.dp
private val ButtonMinHeight = 44.dp
private val ButtonGap = 8.dp
private val ButtonLabelPadding = 12.dp
private val ButtonLabelSize = 17.sp
private val FieldShape = RoundedCornerShape(10.dp)
private const val DISABLED_ALPHA = 0.38f
private const val BUTTON_FILL_ALPHA = 0.1f

/** iOS red, readable on dark and light translucent surfaces (as the context menu's). */
private val DestructiveDark = Color(0xFFFF6961)
private val DestructiveLight = Color(0xFFD70015)

/**
 * A translucent iOS alert, centred over the blurred and dimmed app in the same layer as [TranslucentPanel] (so it
 * samples the app, which a Material dialog's window cannot): a [title], an optional [message], optional [content]
 * (paragraphs, a [TranslucentAlertTextField]) that scrolls when long, and capsule buttons — [confirm], an optional
 * [cancel] and an optional [extra] action — side by side when two fit, stacked otherwise ([translucentAlertButtonLayout]).
 * Back and a tap outside call [onDismiss]. It shows while composed; shown from a panel, it covers that panel.
 *
 * Never compose it inside a Material window (a `ModalBottomSheet`, a `Dialog`): it would open under that window.
 */
@Composable
fun TranslucentAlert(
    title: String,
    onDismiss: () -> Unit,
    confirm: TranslucentAlertButton,
    message: String? = null,
    cancel: TranslucentAlertButton? = null,
    extra: TranslucentAlertButton? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ShowTranslucentPanel(title, onDismiss, anchor = null, maxWidth = AlertWidth) {
        Column {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = LocalTranslucentContentColors.current.label,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    TranslucentAlertText(it)
                }
                content?.let {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = it)
                }
            }
            AlertButtons(confirm, cancel, extra, focusFirst = content == null)
        }
    }
}

/** A paragraph of a [TranslucentAlert]'s message, in its type and the colour for translucent surfaces. */
@Composable
fun TranslucentAlertText(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text,
        color = LocalTranslucentContentColors.current.label,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A text field for a [TranslucentAlert]: opaque, so what is typed stays readable over any translucent surface. */
@Composable
fun TranslucentAlertTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        shape = FieldShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The buttons, laid out by [translucentAlertButtonLayout]; Cancel is bold, or Confirm when it is alone with actions. */
@Composable
private fun AlertButtons(confirm: TranslucentAlertButton, cancel: TranslucentAlertButton?, extra: TranslucentAlertButton?, focusFirst: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize = ButtonLabelSize, fontWeight = FontWeight.SemiBold)
    val labels = listOfNotNull(confirm.label, cancel?.label, extra?.label)
    val widest = remember(labels, density) { labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width } }
    val focus = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        val layout = with(density) {
            translucentAlertButtonLayout(
                hasCancel = cancel != null,
                hasExtra = extra != null,
                widestLabel = widest.toFloat(),
                rowWidth = constraints.maxWidth.toFloat(),
                gap = ButtonGap.toPx(),
                labelPadding = 2 * ButtonLabelPadding.toPx(),
            )
        }
        val preferred = if (cancel != null) TranslucentAlertRole.Cancel else TranslucentAlertRole.Confirm
        val button = @Composable { role: TranslucentAlertRole, modifier: Modifier ->
            val shown = when (role) {
                TranslucentAlertRole.Confirm -> confirm
                TranslucentAlertRole.Cancel -> checkNotNull(cancel)
                TranslucentAlertRole.Extra -> checkNotNull(extra)
            }
            AlertButton(shown, bold = role == preferred, if (role == preferred) modifier.focusRequester(focus) else modifier)
        }
        if (layout.stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(ButtonGap)) {
                for (role in layout.order) button(role, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGap)) {
                for (role in layout.order) button(role, Modifier.weight(1f))
            }
        }
    }
    // Keyboard and switch users start on the preferred button; with a text field, typing starts there instead.
    if (focusFirst) {
        LaunchedEffect(focus) {
            // Not attached yet (or not focusable in touch mode): nothing to move.
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun AlertButton(button: TranslucentAlertButton, bold: Boolean, modifier: Modifier) {
    val translucent = LocalTranslucentContentColors.current
    val tint = when {
        button.destructive -> if (SonoraTheme.isDark) DestructiveDark else DestructiveLight
        else -> translucent.accent
    }
    Text(
        button.label,
        color = if (button.enabled) tint else tint.copy(alpha = DISABLED_ALPHA),
        fontSize = ButtonLabelSize,
        lineHeight = 22.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
            .heightIn(min = ButtonMinHeight)
            .clip(CapsuleShape)
            .background(translucent.label.copy(alpha = BUTTON_FILL_ALPHA))
            .clickable(enabled = button.enabled, role = Role.Button, onClick = button.onClick)
            .padding(horizontal = ButtonLabelPadding, vertical = 11.dp),
    )
}
