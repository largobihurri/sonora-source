package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.icon.SonoraIcons

/**
 * An iOS secure text row for an [InsetGroup]: the bare field (dots while not [visible], a monospaced value when
 * shown, so look-alike characters can be told apart) and a trailing eye that shows or hides it. No autocorrect and
 * no suggestions, so the keyboard learns nothing; [onDone] runs from the keyboard's Done key.
 */
@Composable
fun PasswordFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    placeholder: String,
    showLabel: String,
    hideLabel: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    onDone: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val style = remember(error, visible, colors) {
        TextStyle(
            fontSize = 17.sp,
            color = if (error) colors.error else colors.onSurface,
            fontFamily = if (visible) FontFamily.Monospace else FontFamily.Default,
        )
    }
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).padding(vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    inner()
                }
            },
        )
        IconButton(onClick = { onVisibleChange(!visible) }) {
            Icon(
                if (visible) SonoraIcons.Hidden else SonoraIcons.Visible,
                contentDescription = if (visible) hideLabel else showLabel,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
