package io.sonora.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.R
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import java.util.UUID

/**
 * The app shell's floating bottom chrome (mini player and tab bar) as content sees it. It minimises while content
 * scrolls toward its end; content reserves [contentPadding] for it and reports screens coming and going
 * ([onScreenShown], [onScreenLeft]).
 */
@Stable
interface BottomChrome {
    /** Space to leave at the bottom of scrolling content, following the chrome's current height. */
    val contentPadding: Dp

    /** Asks a minimised chrome to expand again. */
    fun expand()

    /**
     * Content moved by [deltaPx] (positive toward its end) without a scroll gesture: the fast-scroll thumb scrolls the
     * list directly, so nothing reaches the chrome through nested scroll. The chrome treats it like a swipe of that
     * size, so dragging the thumb toward the end minimises it (and the thumb's track grows with it), and back expands it.
     */
    fun onFastScroll(deltaPx: Float) = Unit

    /**
     * The screen [screen] (an identity it keeps while it is on its back stack) shows. Another screen than the one shown
     * last is a push or a pop: the chrome keeps its size and forgets the previous screen's travel. The same screen again
     * (its header scrolled back into its list) changes nothing.
     */
    fun onScreenShown(screen: Any)

    /** The screen [screen] left (a pop, or a push over it): the chrome keeps its size and forgets its travel. */
    fun onScreenLeft(screen: Any)
}

/** A fixed chrome that never minimises: previews, tests and screens shown outside the app shell. */
private object FixedBottomChrome : BottomChrome {
    override val contentPadding: Dp = 170.dp

    override fun expand() = Unit

    override fun onScreenShown(screen: Any) = Unit

    override fun onScreenLeft(screen: Any) = Unit
}

/** The chrome of the app shell; static because the value is one stable object whose padding is snapshot state. */
val LocalBottomChrome = staticCompositionLocalOf<BottomChrome> { FixedBottomChrome }

/** Space reserved at the bottom of scrolling content for the mini player and tab bar, as tall as they are now. */
val BottomChromePadding: Dp
    @Composable
    @ReadOnlyComposable
    get() = LocalBottomChrome.current.contentPadding

/**
 * Tells the bottom chrome when the calling screen appears and when it leaves (push and pop); its size is kept. Call it
 * from the screen's root, which stays composed while the screen shows (not from a lazy list item).
 */
@Composable
fun ReportScreenToBottomChrome() {
    val chrome = LocalBottomChrome.current
    val screen = rememberScreenIdentity()
    DisposableEffect(chrome, screen) {
        chrome.onScreenShown(screen)
        onDispose { chrome.onScreenLeft(screen) }
    }
}

/**
 * Tells the bottom chrome that the calling screen shows, from content that may leave composition while the screen
 * stays, such as a lazy list item scrolled off: only a screen the chrome has not just seen counts as a push or a pop.
 */
@Composable
private fun ReportScreenShownToBottomChrome() {
    val chrome = LocalBottomChrome.current
    val screen = rememberScreenIdentity()
    DisposableEffect(chrome, screen) {
        chrome.onScreenShown(screen)
        onDispose { }
    }
}

/** An identity for the calling screen, kept in its saved state: a lazy item scrolled off and back keeps it. */
@Composable
private fun rememberScreenIdentity(): String = rememberSaveable { UUID.randomUUID().toString() }

/** Expands a minimised bottom chrome when the calling sheet appears: a sheet shows the full chrome behind it, as on iOS. */
@Composable
fun ExpandBottomChromeBehindSheet() {
    val chrome = LocalBottomChrome.current
    LaunchedEffect(chrome) { chrome.expand() }
}

/** iOS large navigation title. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
    )
}

/**
 * iOS 26 back button: an accent chevron in a translucent circle, inset [TranslucentBarInset] from the bar's top, bottom
 * and leading edge. TalkBack reads the previous screen's [label] as the action. Its screen keeps the bottom chrome's
 * size.
 */
@Composable
fun BackHeader(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Every pushed screen starts with a back header, often the first item of its list: a new screen showing it is a
    // push or a pop, the same screen scrolling it off and back is not.
    ReportScreenShownToBottomChrome()
    Box(modifier.padding(horizontal = 16.dp, vertical = TranslucentBarInset)) {
        TranslucentBarCircle(SonoraIcons.ChevronLeft, stringResource(R.string.ds_back), onBack, onClickLabel = label)
    }
}

/** A translucent bar button's size: iOS 26's 44 pt circle and capsule height, also its minimum touch target. */
val TranslucentBarButtonSize = 44.dp

/** The gap above and below a translucent bar button inside its bar. */
val TranslucentBarInset = 4.dp

/** A floating top bar's height: a [TranslucentBarButtonSize] button with [TranslucentBarInset] above and below. */
val TranslucentBarHeight: Dp = TranslucentBarButtonSize + TranslucentBarInset * 2

/**
 * A tab's top bar holding only its Settings gear, where the Library's bar has it: at the end of a [TranslucentBarHeight] band,
 * 16 dp in. Its translucency samples `LocalTranslucentBackdrop`, so place it outside the content that backdrop records.
 */
@Composable
fun TranslucentSettingsBar(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(TranslucentBarHeight).padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
        TranslucentBarCircle(SonoraIcons.Settings, stringResource(R.string.ds_settings), onSettings)
    }
}

/**
 * A translucent bar button's shadow: clear translucency over a plain background (a white row) shows nothing else, so
 * without it the button vanishes there; the soft shadow keeps its bubble visible, as the tab bar's and mini player's
 * keep theirs.
 */
private val TranslucentBarElevation = 6.dp

/**
 * A round translucent bar button (back, "…", Sort): clear translucency sampling `LocalTranslucentBackdrop`, flat
 * without one, with an accent glyph and a soft shadow ([TranslucentBarElevation]). The whole circle is the touch
 * target.
 */
@Composable
fun TranslucentBarCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    TranslucentSurface(modifier.size(TranslucentBarButtonSize), shape = CircleShape, elevation = TranslucentBarElevation, tinted = false) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = LocalTranslucentContentColors.current.accent)
        }
    }
}

/**
 * A translucent text bar button ("Select", "Done", "Cancel"): a 44 dp capsule of clear translucency sampling
 * `LocalTranslucentBackdrop`, flat without one, with an accent label and a soft shadow; [emphasized] sets it semibold, as
 * iOS sets the confirming button.
 */
@Composable
fun TranslucentBarCapsule(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    TranslucentSurface(modifier.height(TranslucentBarButtonSize), shape = CapsuleShape, elevation = TranslucentBarElevation, tinted = false) {
        Box(
            // Full height, so the label and its touch area are centred and never hug the top of the capsule.
            Modifier.fillMaxHeight().clip(CapsuleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = LocalTranslucentContentColors.current.accent,
                maxLines = 1,
            )
        }
    }
}

/** Hairline separator inset from the leading edge, as between iOS list rows. */
@Composable
fun InsetDivider(start: Dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
