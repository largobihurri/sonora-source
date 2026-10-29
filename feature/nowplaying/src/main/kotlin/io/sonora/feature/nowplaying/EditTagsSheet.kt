package io.sonora.feature.nowplaying

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.sonora.core.designsystem.R as DesignR
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.ContextMenuHost
import io.sonora.core.designsystem.component.FormSheet
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.InsetGroup
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.TagField
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The rows of the form, in groups: song details, album details, the comment. */
private val SONG_FIELDS = listOf(TagField.TITLE, TagField.ARTIST, TagField.GENRE, TagField.YEAR, TagField.BPM, TagField.KEY)
private val ALBUM_FIELDS = listOf(TagField.ALBUM, TagField.ALBUM_ARTIST, TagField.TRACK_NUMBER, TagField.TRACK_TOTAL, TagField.DISC_NUMBER, TagField.DISC_TOTAL)

/**
 * The tag edit form opened from File Info: Cancel / Edit Info / Save, the artwork with Choose Image… and Remove
 * Image, then the fields as label + text rows in inset groups (track and disc take a number and a total). Errors show
 * under their group as the user types; Save stays dimmed until something changed and every change is valid. A
 * read-only folder shows why, with Choose Folder… opening the system picker on that folder. Cancelling a changed
 * form asks first ("Discard changes?") in a [TranslucentAlert] over the form.
 */
@Composable
internal fun EditTagsSheet(state: TagEditorState, viewModel: TagEditorViewModel) {
    val strings = rememberStrings()
    val editing = state as? TagEditorState.Editing
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var noPicker by rememberSaveable { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.pickPicture(it.toString()) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.accessPicked(it.toString()) }
    }
    // The alerts only show over the form (see below).
    val alertOpen = editing != null && (confirmDiscard || noPicker)
    val cancel = {
        when (tagEditCancel(alertOpen, dirty = editing?.dirty == true)) {
            TagEditCancel.CloseAlert -> {
                confirmDiscard = false
                noPicker = false
            }
            TagEditCancel.ConfirmDiscard -> confirmDiscard = true
            TagEditCancel.Close -> viewModel.close()
        }
    }

    FormSheet(
        title = stringResource(R.string.tag_edit_title),
        cancelLabel = stringResource(R.string.tag_edit_cancel),
        confirmLabel = stringResource(R.string.tag_edit_save),
        // The header stays outside an alert's dim: Save waits until the alert is answered.
        confirmEnabled = editing?.canSave == true && !alertOpen,
        busy = editing?.saving == true,
        dismissible = editing?.dirty != true,
        onCancel = cancel,
        onConfirm = viewModel::save,
    ) {
        when (state) {
            TagEditorState.Closed, is TagEditorState.Saved -> Unit
            TagEditorState.Loading -> Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) { CircularProgressIndicator() }
            is TagEditorState.Unavailable -> Message(TagEditText.failure(state.failure, strings))
            // The alerts come from the form, which only this state shows. The sheet is its own window, which the app
            // shell's overlay host draws under and cannot sample: a host of the sheet's own shows them over the form,
            // blurring and sampling it, in the sheet's window.
            is TagEditorState.Editing -> ContextMenuHost(Modifier.fillMaxWidth()) {
                ReleaseFieldFocus(alertOpen)
                EditForm(state, viewModel, onPickFolder = pickFolder::launch, onPickImage = pickImage::launch, onNoPicker = { noPicker = true })
                if (confirmDiscard) {
                    TranslucentAlert(
                        title = stringResource(R.string.tag_edit_discard_title),
                        onDismiss = { confirmDiscard = false },
                        confirm = TranslucentAlertButton(stringResource(R.string.tag_edit_discard), destructive = true) {
                            confirmDiscard = false
                            viewModel.close()
                        },
                        cancel = TranslucentAlertButton(stringResource(R.string.tag_edit_keep_editing)) { confirmDiscard = false },
                    )
                }
                if (noPicker) {
                    TranslucentAlert(
                        title = stringResource(R.string.tag_edit_no_picker),
                        onDismiss = { noPicker = false },
                        confirm = TranslucentAlertButton(stringResource(DesignR.string.ds_done)) { noPicker = false },
                    )
                }
            }
        }
    }
}

/**
 * Takes focus off the form's field when an alert opens ([alertOpen]) and when the keyboard goes away
 * ([ImeHideDetector]: only once it was shown, so a field just focused keeps its focus). A field that kept focus after
 * Back hid the keyboard brought it back when the alert opened, and the keyboard then squeezed the alert; with no
 * focused field, Back closes the keyboard, then the alert, then the sheet. Called inside the sheet, whose window owns
 * the field's focus and keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseFieldFocus(alertOpen: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(alertOpen) {
        if (alertOpen) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    val imeHide = remember { ImeHideDetector() }
    LaunchedEffect(imeVisible) {
        if (imeHide.update(imeVisible)) focus.clearFocus()
    }
}

/**
 * Tells when the keyboard goes away: [update] answers true only on a shown → hidden change. Not on the first report or
 * while it has not been shown yet, so a field that asks for focus before the keyboard's inset reports it visible keeps
 * its focus.
 */
internal class ImeHideDetector {
    private var wasVisible = false

    fun update(visible: Boolean): Boolean = (wasVisible && !visible).also { wasVisible = visible }
}

/** The form: a read-only folder's problem, the artwork, then the song, album and comment groups. */
@Composable
private fun EditForm(
    state: TagEditorState.Editing,
    viewModel: TagEditorViewModel,
    onPickFolder: (Uri) -> Unit,
    onPickImage: (PickVisualMediaRequest) -> Unit,
    onNoPicker: () -> Unit,
) {
    val strings = rememberStrings()
    LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        state.problem?.let { problem ->
            item(key = "problem") {
                val request = when (problem) {
                    is EditProblem.NeedsAccess -> problem.request
                    is EditProblem.WrongFolder -> problem.request
                    else -> null
                }
                InsetGroup(header = null) {
                    Text(
                        TagEditText.problem(problem, strings),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (request != null) {
                        InsetDivider(start = 16.dp)
                        ButtonRow(stringResource(R.string.tag_edit_choose_folder)) {
                            try {
                                onPickFolder(request.pickerStartUri.toUri())
                            } catch (_: ActivityNotFoundException) {
                                onNoPicker()
                            }
                        }
                    }
                }
            }
        }
        item(key = "artwork") {
            ArtworkEditor(
                picture = state.form.shownArtwork(state.original),
                loading = state.loadingPicture,
                onChoose = {
                    try {
                        onPickImage(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: ActivityNotFoundException) {
                        onNoPicker()
                    }
                },
                onRemove = viewModel::removePicture,
            )
        }
        item(key = "song") {
            InsetGroup(stringResource(R.string.tag_edit_section_song), footer = footer(state, SONG_FIELDS, strings)) {
                SONG_FIELDS.forEachIndexed { index, field ->
                    if (index > 0) InsetDivider(start = 16.dp)
                    FieldRow(field, state, viewModel)
                }
            }
        }
        item(key = "album") {
            InsetGroup(stringResource(R.string.tag_edit_section_album), footer = footer(state, ALBUM_FIELDS, strings)) {
                FieldRow(TagField.ALBUM, state, viewModel)
                InsetDivider(start = 16.dp)
                FieldRow(TagField.ALBUM_ARTIST, state, viewModel)
                InsetDivider(start = 16.dp)
                PairRow(TagField.TRACK_NUMBER, TagField.TRACK_TOTAL, state, viewModel)
                InsetDivider(start = 16.dp)
                PairRow(TagField.DISC_NUMBER, TagField.DISC_TOTAL, state, viewModel)
            }
        }
        item(key = "comment") {
            InsetGroup(stringResource(R.string.file_info_comment)) {
                EditField(
                    value = state.form[TagField.COMMENT],
                    onValueChange = { viewModel.change(TagField.COMMENT, it) },
                    enabled = !state.saving,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** What Cancel does (the header button, Back, a tap outside the sheet). */
internal enum class TagEditCancel { CloseAlert, ConfirmDiscard, Close }

/**
 * Cancel first closes an [alertOpen] (the alert sits in the sheet's body, under its header, so the header and Back
 * still reach the sheet); otherwise it asks before discarding a [dirty] form and closes an untouched one.
 */
internal fun tagEditCancel(alertOpen: Boolean, dirty: Boolean): TagEditCancel = when {
    alertOpen -> TagEditCancel.CloseAlert
    dirty -> TagEditCancel.ConfirmDiscard
    else -> TagEditCancel.Close
}

private fun footer(state: TagEditorState.Editing, fields: List<TagField>, strings: Strings): String? =
    TagEditText.errors(state.errors, fields, strings).joinToString("\n").ifEmpty { null }

@Composable
private fun Message(text: String) {
    Text(
        text,
        fontSize = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
    )
}

/** The artwork, centered, with its two actions under it. */
@Composable
private fun ArtworkEditor(picture: Picture?, loading: Boolean, onChoose: () -> Unit, onRemove: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, picture) { value = picture?.let { decodeThumbnail(it.bytes) } }
    InsetGroup(header = null) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), Alignment.Center) {
            val shape = RoundedCornerShape(10.dp)
            val image = bitmap
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(136.dp).clip(shape))
            } else {
                Artwork(model = null, shape = shape, modifier = Modifier.size(136.dp))
            }
            if (loading) CircularProgressIndicator()
        }
        ButtonRow(stringResource(R.string.tag_edit_choose_image), enabled = !loading, onClick = onChoose)
        if (picture != null) {
            InsetDivider(start = 16.dp)
            ButtonRow(stringResource(R.string.tag_edit_remove_image), enabled = !loading, destructive = true, onClick = onRemove)
        }
    }
}

/** Decodes [bytes] down to about thumbnail size, off the main thread; `null` when they are not an image. */
private suspend fun decodeThumbnail(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private const val THUMBNAIL_PX = 400

@Composable
private fun ButtonRow(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 17.sp,
            color = when {
                !enabled -> colors.onSurfaceVariant
                destructive -> colors.error
                else -> colors.primary
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One field: its label, then the text field filling the row. */
@Composable
private fun FieldRow(field: TagField, state: TagEditorState.Editing, viewModel: TagEditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(stringResource(TagEditText.label(field)))
        EditField(
            value = state.form[field],
            onValueChange = { viewModel.change(field, it) },
            enabled = !state.saving,
            error = field in state.errors,
            keyboard = keyboardFor(field),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Track or disc: the number and the total side by side ("3 / 12"). */
@Composable
private fun PairRow(number: TagField, total: TagField, state: TagEditorState.Editing, viewModel: TagEditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(stringResource(TagEditText.label(number)))
        EditField(
            value = state.form[number],
            onValueChange = { viewModel.change(number, it) },
            enabled = !state.saving,
            error = number in state.errors,
            keyboard = keyboardFor(number),
            placeholder = stringResource(R.string.tag_edit_number),
            modifier = Modifier.weight(1f),
        )
        Text("/", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        EditField(
            value = state.form[total],
            onValueChange = { viewModel.change(total, it) },
            enabled = !state.saving,
            error = total in state.errors,
            keyboard = keyboardFor(total),
            placeholder = stringResource(R.string.tag_edit_total),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(LABEL_WIDTH).padding(end = 12.dp))
}

private val LABEL_WIDTH = 116.dp

private fun keyboardFor(field: TagField): KeyboardOptions = when {
    field.numeric || field == TagField.YEAR -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
    field == TagField.KEY -> KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next)
    else -> KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
}

/** A bare iOS-style text field: no box, the value in the body colour, the placeholder dimmed, red while invalid. */
@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    singleLine: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    placeholder: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = remember(error, colors) { TextStyle(fontSize = 17.sp, color = if (error) colors.error else colors.onSurface) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = style,
        keyboardOptions = keyboard,
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, fontSize = 17.sp, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                }
                inner()
            }
        },
    )
}
