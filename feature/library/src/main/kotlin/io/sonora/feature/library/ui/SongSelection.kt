package io.sonora.feature.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentBarCapsule
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.R
import java.text.NumberFormat
import kotlin.math.floor

/**
 * Selection mode over one song list: whether it is on and which rows are picked, by the row's key (a song's URI, or
 * a playlist entry's id, so a song listed twice is two rows). Pure: the screen keeps one in its state.
 */
@Immutable
internal data class SelectionState(val active: Boolean = false, val keys: Set<String> = emptySet()) {
    /** "Select" (nothing picked) or a long-press "Select" ([key] picked). */
    fun start(key: String? = null): SelectionState = SelectionState(active = true, keys = setOfNotNull(key))

    /** A tap on row [key] while selecting; outside selection nothing changes. */
    fun toggle(key: String): SelectionState = if (!active) this else copy(keys = if (key in keys) keys - key else keys + key)

    /** Select All, or Deselect All once every row [shown] is picked. */
    fun toggleAll(shown: List<String>): SelectionState =
        if (!active) this else copy(keys = if (allSelected(shown)) emptySet() else shown.toSet())

    fun allSelected(shown: List<String>): Boolean = shown.isNotEmpty() && keys.containsAll(shown)

    /** How many rows [shown] are picked (rows gone from the list meanwhile do not count). */
    fun count(shown: List<String>): Int = shown.count { it in keys }

    /** The picked songs in the order [shown] lists them, whatever the order they were tapped in. */
    fun <T> picked(shown: List<Pair<String, T>>): List<T> = shown.filter { it.first in keys }.map { it.second }

    /** A drag over the rows ([DragPaint]) leaves exactly [picked] picked; outside selection nothing changes. */
    fun paint(picked: Set<String>): SelectionState = if (!active) this else copy(keys = picked)

    /** Done or Back. */
    fun exit(): SelectionState = SelectionState()
}

/** What the selection bar does with the picked songs. */
internal enum class SelectionAction { COMPRESS, ADD_TO_PLAYLIST, EXPORT_REKORDBOX, PLAY_NEXT, PLAY_LAST }

/** The selection bar: its actions in order and the ones dimmed. */
internal data class SelectionBarModel(val actions: List<SelectionAction>, val disabled: Set<SelectionAction>) {
    companion object {
        /** With [count] songs picked, and whether the CDJ export takes songs now ([canExport]). */
        fun of(count: Int, canExport: Boolean): SelectionBarModel {
            val actions = SelectionAction.entries.toList()
            val disabled = when {
                count == 0 -> actions.toSet()
                !canExport -> setOf(SelectionAction.EXPORT_REKORDBOX)
                else -> emptySet()
            }
            return SelectionBarModel(actions, disabled)
        }
    }
}

/**
 * What the selection bar hands to its screen (the Library or Search); a list under a screen without them offers no
 * "Select". [name] is the list's title: the archive's and the Rekordbox playlist's name.
 */
internal class SelectionActions(
    val canExport: Boolean,
    val onCompress: (name: String, tracks: List<Track>) -> Unit,
    val onAddToPlaylist: (List<Track>) -> Unit,
    val onExport: (name: String, tracks: List<Track>) -> Unit,
    val onQueue: (List<Track>, MenuAction) -> Unit,
)

internal val LocalSelectionActions = staticCompositionLocalOf<SelectionActions?> { null }

/** One list's selection mode, as Compose state. */
@Stable
internal class SongSelection {
    var state by mutableStateOf(SelectionState())
        private set

    val active: Boolean get() = state.active

    fun start(key: String? = null) {
        state = state.start(key)
    }

    fun toggle(key: String) {
        state = state.toggle(key)
    }

    fun toggleAll(shown: List<String>) {
        state = state.toggleAll(shown)
    }

    fun exit() {
        state = state.exit()
    }

    /** A drag over the rows leaves exactly [picked] picked ([DragPaint]). */
    fun paint(picked: Set<String>) {
        state = state.paint(picked)
    }

    /** The rows on screen while selecting, by key, for the drag to find the one under the finger ([dragSelectRow]). */
    val rowCoordinates: MutableMap<String, LayoutCoordinates> = HashMap()

    /** Row [key]'s check mark: `null` outside selection, so the row shows none. */
    fun mark(key: String): Boolean? = if (state.active) key in state.keys else null

    /** A row's tap: picks or unpicks it while selecting, else [normal] (play). */
    fun click(key: String, normal: () -> Unit): () -> Unit = { if (state.active) toggle(key) else normal() }

    /** A row's long-press: picks or unpicks it while selecting, else [normal] (the song menu). */
    fun longClick(key: String, normal: () -> Unit): () -> Unit = { if (state.active) toggle(key) else normal() }
}

/** Room the selection bar takes over a list's end while selecting. */
internal val SelectionBarSpace: Dp = 96.dp

/** A song list's selection mode; `null` under a screen whose selection bar has nothing to do. Back ends it. */
@Composable
internal fun rememberSongSelection(): SongSelection? {
    val actions = LocalSelectionActions.current
    val selection = remember { SongSelection() }
    BackHandler(enabled = actions != null && selection.active) { selection.exit() }
    return selection.takeIf { actions != null }
}

/** The "Select" button of a list's top bar: a translucent capsule. */
@Composable
internal fun SelectButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TranslucentBarCapsule(stringResource(R.string.select_action), onClick, modifier)
}

/**
 * Whether a title laid out with lines starting at [lineStarts] wraps only between words: every line after the first
 * starts right after a space or a hyphen of the text. A word wider than the line is split anywhere by the line breaker
 * (no hyphen drawn), whatever the hyphenation or word-break style, so this is checked on the layout.
 */
internal fun breaksOnlyBetweenWords(text: CharSequence, lineStarts: List<Int>): Boolean = lineStarts.all { start ->
    start <= 0 || start > text.length || text[start - 1].isWhitespace() || text[start - 1] == '-'
}

/** The largest size from [max] down to [min] in [step]s (positive) that [fits]; [min] when none does. */
internal fun largestFittingSize(max: Float, min: Float, step: Float, fits: (Float) -> Boolean): Float =
    firstSize(max, min, step) { if (fits(it)) it else null } ?: min

/** How a title lays out at one size. */
internal enum class TitleFit {
    /** Cut: wider or taller than its room, or ellipsized. */
    Overflows,

    /** Not cut, but a line starts inside a word ([breaksOnlyBetweenWords]). */
    SplitsWord,

    /** Not cut, and wrapped only between words. */
    Fits,
}

/**
 * The largest size from [max] down to [min] in [step]s (positive) that [TitleFit.Fits]. When a word is wider than the
 * line at every size (a long single-word translation, a large font scale), splitting it is unavoidable: the largest
 * size that is at least not cut wins, as [TextAutoSize.StepBased] would pick, rather than [min]. [min] when every size
 * is cut. One [measure] per size.
 */
internal fun wordSafeSize(max: Float, min: Float, step: Float, measure: (Float) -> TitleFit): Float {
    var largestUncut: Float? = null
    return firstSize(max, min, step) { size ->
        when (measure(size)) {
            TitleFit.Fits -> size
            TitleFit.SplitsWord -> {
                if (largestUncut == null) largestUncut = size
                null
            }
            TitleFit.Overflows -> null
        }
    } ?: largestUncut ?: min
}

/** The first non-null [pick] of the sizes from [max] down to [min] in [step]s. */
private inline fun firstSize(max: Float, min: Float, step: Float, pick: (Float) -> Float?): Float? {
    require(step > 0f) { "Step $step must be positive" }
    val steps = floor((max - min) / step + STEP_EPSILON).toInt()
    for (i in 0..steps) {
        pick(max - i * step)?.let { return it }
    }
    return null
}

private const val STEP_EPSILON = 1e-4f

/**
 * [TextAutoSize.StepBased] that also steps down while a word would be split across lines: the step-based size only
 * shrinks on overflow, and "Seleccionar canciones" at 17 sp fits two lines as "Selecciona" / "r canciones". The
 * largest size that fits without ellipsis and wraps only between words wins ([wordSafeSize]); when a word is too wide
 * at every size, the largest that is not cut; [minFontSize] when every size is cut.
 */
private data class WordSafeAutoSize(val minFontSize: TextUnit, val maxFontSize: TextUnit, val step: TextUnit = 0.25.sp) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val size = wordSafeSize(maxFontSize.value, minFontSize.value, step.value) { candidate ->
            val layout = performLayout(constraints, text, candidate.sp)
            val last = layout.lineCount - 1
            when {
                layout.didOverflowWidth || layout.didOverflowHeight || (last >= 0 && layout.isLineEllipsized(last)) ->
                    TitleFit.Overflows
                breaksOnlyBetweenWords(text.text, List(layout.lineCount) { layout.getLineStart(it) }) -> TitleFit.Fits
                else -> TitleFit.SplitsWord
            }
        }
        return size.sp
    }
}

/** The selection count's largest and smallest size; it shrinks and takes a second line before it is cut. */
private val CountMaxSize = 17.sp
private val CountMinSize = 12.sp

/**
 * The top bar while selecting, for a [TranslucentBarPage] with its `scrollEdge` on (the count is plain text over the rows):
 * Select All / Deselect All and Done as translucent capsules matching the selection bar, and how many are picked
 * centred between them. On a narrow phone the capsules leave the count little room (about 58 dp at 320 dp wide in
 * Spanish once the leading capsule reads "Deseleccionar todo"), so the count shows the bare number, as iOS does;
 * TalkBack still reads the full phrase ("3 songs selected"). Before anything is picked it shows "Select Songs",
 * which shrinks down to [CountMinSize] and wraps to two lines, only between words ([WordSafeAutoSize]), before it is
 * cut.
 */
@Composable
internal fun SelectionHeader(selection: SongSelection, shownKeys: List<String>, modifier: Modifier = Modifier) {
    val count = selection.state.count(shownKeys)
    val countPhrase = if (count == 0) null else pluralStringResource(R.plurals.picker_selected, count, count)
    val locale = LocalConfiguration.current.locales[0]
    TranslucentTopBar(
        modifier,
        leading = {
            TranslucentBarCapsule(
                stringResource(if (selection.state.allSelected(shownKeys)) R.string.picker_deselect_all else R.string.picker_select_all),
                onClick = { selection.toggleAll(shownKeys) },
            )
        },
        title = {
            BasicText(
                if (countPhrase == null) stringResource(R.string.select_songs) else NumberFormat.getIntegerInstance(locale).format(count),
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = CountMaxSize,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.15.em,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = WordSafeAutoSize(minFontSize = CountMinSize, maxFontSize = CountMaxSize),
                modifier = Modifier.semantics {
                    heading()
                    if (countPhrase != null) contentDescription = countPhrase
                },
            )
        },
        trailing = { TranslucentBarCapsule(stringResource(R.string.select_done), onClick = selection::exit, emphasized = true) },
    )
}

/**
 * The floating translucent bar of a list in selection mode: Compress, Add to Playlist, Export as Rekordbox Playlist,
 * Play Next and Play Last for the picked songs ([shown] in list order); each ends the selection. [name] is the list's
 * title. Place it at the bottom of the list's box, above the app's bottom chrome, outside the content its translucency
 * samples (a [TranslucentBarPage]'s `overlay`): it samples `LocalTranslucentBackdrop` and draws flat without one.
 * [darkTheme] picks the dark translucency, as over a tinted page.
 */
@Composable
internal fun SelectionBar(
    selection: SongSelection,
    shown: List<Pair<String, Track>>,
    name: String,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = SonoraTheme.isDark,
) {
    val actions = LocalSelectionActions.current ?: return
    if (!selection.active) return
    val picked = selection.state.picked(shown)
    val model = SelectionBarModel.of(picked.size, actions.canExport)
    TranslucentSurface(modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), darkTheme = darkTheme) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (action in model.actions) {
                val (icon, label) = action.look()
                val enabled = action !in model.disabled
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(
                        enabled = enabled,
                        onClick = {
                            when (action) {
                                SelectionAction.COMPRESS -> actions.onCompress(name, picked)
                                SelectionAction.ADD_TO_PLAYLIST -> actions.onAddToPlaylist(picked)
                                SelectionAction.EXPORT_REKORDBOX -> actions.onExport(name, picked)
                                SelectionAction.PLAY_NEXT -> actions.onQueue(picked, MenuAction.PLAY_NEXT)
                                SelectionAction.PLAY_LAST -> actions.onQueue(picked, MenuAction.PLAY_LAST)
                            }
                            selection.exit()
                        },
                    ) {
                        val tint = LocalTranslucentContentColors.current.accent
                        Icon(
                            icon,
                            contentDescription = stringResource(label),
                            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun SelectionAction.look(): Pair<ImageVector, Int> = when (this) {
    SelectionAction.COMPRESS -> SonoraIcons.Archive to R.string.compress_action
    SelectionAction.ADD_TO_PLAYLIST -> SonoraIcons.Add to R.string.playlist_add_to
    SelectionAction.EXPORT_REKORDBOX -> SonoraIcons.Usb to R.string.menu_export_rekordbox
    SelectionAction.PLAY_NEXT -> SonoraIcons.PlayNext to R.string.queue_play_next
    SelectionAction.PLAY_LAST -> SonoraIcons.PlayLast to R.string.menu_play_last
}

/** The column of a list's rows with their keys, for [SelectionBar] and [SelectionHeader]. */
internal fun List<Track>.keyedByUri(): List<Pair<String, Track>> = map { it.uri to it }
