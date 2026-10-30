package io.sonora.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.feature.library.search.SEARCH_CAPSULE_HEIGHT

/** Placeholder and glyphs: the translucent label colour, quieter than the typed text. */
private const val SECONDARY_ALPHA = 0.7f

/**
 * iOS 26 search: a translucent capsule with magnifier, field and clear button, sampling [backdrop] (the results
 * behind it). While [focused], a translucent "Cancel" capsule beside it empties the field and puts the keyboard away
 * ([onCancel]). The keyboard's search key calls [onSearch]; [onFocusChange] reports the field's focus.
 */
@Composable
internal fun SearchCapsule(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    backdrop: TranslucentBackdrop,
    modifier: Modifier = Modifier,
) {
    val height = SEARCH_CAPSULE_HEIGHT.dp
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TranslucentSurface(Modifier.weight(1f).height(height), shape = CapsuleShape, backdrop = backdrop, overContent = true) {
            val colors = LocalTranslucentContentColors.current
            val secondary = colors.label.copy(alpha = SECONDARY_ALPHA)
            // The whole capsule focuses the field, not only the text line.
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(CapsuleShape)
                    .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SonoraIcons.Search, contentDescription = null, tint = secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val placeholder = stringResource(R.string.search_placeholder)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.label),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = secondary, maxLines = 1)
                        inner()
                    },
                )
                if (value.isNotEmpty()) {
                    val clear = stringResource(R.string.search_clear)
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(height)
                            .clickable(role = Role.Button, onClickLabel = clear) { onValueChange("") }
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(SonoraIcons.Clear, contentDescription = null, tint = secondary, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.width(14.dp))
                }
            }
        }
        if (focused) {
            TranslucentSurface(Modifier.height(height), shape = CapsuleShape, backdrop = backdrop, overContent = true) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(CapsuleShape)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.search_cancel), color = LocalTranslucentContentColors.current.label, maxLines = 1)
                }
            }
        }
    }
}
