package io.sonora.feature.library.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.ReportScreenToBottomChrome
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.designsystem.layout.ContentWidthBox
import io.sonora.core.designsystem.layout.LocalContentArea
import io.sonora.core.designsystem.layout.TopChromeSpace
import io.sonora.core.designsystem.motion.LocalStackPull
import io.sonora.core.designsystem.motion.StackPull
import io.sonora.core.designsystem.motion.slidingPageBackground
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.CHROME_TINT_MILLIS
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.TintChrome
import io.sonora.core.designsystem.theme.rememberArtworkPalette
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.R

/** The colours of a tinted page, each blending to a new artwork's over [CHROME_TINT_MILLIS]. */
@Immutable
internal data class PageColors(
    val background: Color,
    val lowerBand: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val buttonFill: Color,
    val divider: Color,
)

private val NeutralPalette = ArtworkPalette.from(null)

/** Text on the white Play capsule. */
private val PlayLabel = Color(0xFF111113)

@Composable
private fun animatedColors(palette: ArtworkPalette): PageColors {
    @Composable
    fun animate(argb: Int): Color = animateColorAsState(Color(argb), tween(CHROME_TINT_MILLIS), label = "pageTint").value
    return PageColors(
        background = animate(palette.background),
        lowerBand = animate(palette.lowerBand),
        secondaryText = animate(palette.secondaryText),
        mutedText = animate(palette.mutedText),
        buttonFill = animate(palette.buttonFill),
        divider = animate(palette.divider),
    )
}

/**
 * An album or artist page tinted with [artworkUri]'s dominant colour (iOS 26 Music): a solid dark page in that hue,
 * white titles, translucent back and "…" buttons floating over the content, and the app chrome tinted the same way
 * while the page is shown ([TintChrome]). Rows inside read white text from the theme's `onBackground`.
 */
@Composable
internal fun TintedPage(
    artworkUri: String?,
    onBack: () -> Unit,
    onMore: (() -> Unit)?,
    select: PageSelection? = null,
    content: LazyListScope.(PageColors) -> Unit,
) {
    ReportScreenToBottomChrome()
    val loaded = rememberArtworkPalette(artworkUri)
    // The tint is known once the artwork is read; until then the page shows the neutral one, and so does the strip under a sidebar.
    TintChrome(loaded, backdrop = loaded ?: NeutralPalette)
    LightStatusBarIcons()
    val colors = animatedColors(loaded ?: NeutralPalette)
    val translucent = rememberTranslucentBackdrop()
    val scheme = MaterialTheme.colorScheme
    val tinted = remember(scheme, colors) {
        scheme.copy(
            background = colors.background,
            onBackground = Color.White,
            onSurfaceVariant = colors.mutedText,
            outlineVariant = colors.divider,
        )
    }
    val list = rememberLazyListState()
    ScrollToTopEffect(list)
    val pull = LocalStackPull.current
    val flingVelocity = with(LocalDensity.current) { PullFlingVelocity.toPx() }
    val pullGesture = if (pull != null) Modifier.pointerInput(pull, list) { detectPullToClose(pull, list, flingVelocity) } else Modifier
    Box(Modifier.fillMaxSize().then(pullGesture)) {
        // The page's own colour, its edge fading into the next tab while the tab strip slides.
        Box(Modifier.fillMaxSize().translucentBackdropSource(translucent).slidingPageBackground(colors.background)) {
            MaterialTheme(colorScheme = tinted) {
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val dragSelect = if (select != null) {
                    Modifier.dragSelect(select.selection, list, select.shown, topInset = statusBarTop + TopBarHeight)
                } else {
                    Modifier
                }
                // On a large window the blocks (see PageColumn) keep the table width and the rows below know their room.
                ContentWidthBox(Modifier.fillMaxSize(), ContentWidth.TABLE_DP.dp) {
                    LazyColumn(Modifier.fillMaxSize().then(dragSelect), state = list, contentPadding = PaddingValues(bottom = 0.dp)) {
                        item(key = "topInset") {
                            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                            // The bar's row, or under the shell's top pill on a large screen with the sidebar hidden.
                            TopChromeSpace(own = TopBarHeight)
                        }
                        content(colors)
                        if (select?.selection?.active == true) item(key = "selectionBar") { Spacer(Modifier.height(SelectionBarSpace)) }
                    }
                }
            }
        }
        TopBar(translucent, colors.buttonFill, onBack, onMore, select)
        if (select != null) {
            // Outside the recorded content, sampling the page as its top bar does, on the page's dark translucency.
            CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) {
                SelectionBar(
                    select.selection, select.shown, select.name,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding),
                    darkTheme = true,
                )
            }
        }
    }
}

/** A tinted page's song selection: its [selection], the songs [shown] in list order, and the page's [name]. */
internal class PageSelection(val selection: SongSelection, val shown: List<Pair<String, Track>>, val name: String)

private val TopBarHeight = 60.dp

/** A release at least this fast downwards closes the page however little it was pulled. */
private val PullFlingVelocity = 1000.dp

/** Key of the page's header item (artwork and titles), where a pull to close may start. */
internal const val PAGE_HEADER_KEY = "header"

/**
 * Pulls the page down with the finger when a drag starts on the header while the list is at its top (see
 * [PullToClose]); otherwise the list scrolls as usual. Watched on the initial pass so a claimed drag is consumed
 * before the list sees it. Moves are measured as finger deltas, which the page moving under the finger does not
 * change.
 */
private suspend fun PointerInputScope.detectPullToClose(pull: StackPull, list: LazyListState, flingVelocity: Float) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val header = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PAGE_HEADER_KEY }
        val headerBottom = header?.let { (it.offset + it.size).toFloat() }
        if (!PullToClose.canStart(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, down.position.y, headerBottom)) {
            return@awaitEachGesture
        }
        val height = size.height.toFloat()
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        var fingerY = 0f
        var travel = 0f
        var offset = 0f
        var pulling = false
        var close = false
        tracker.addPosition(down.uptimeMillis, Offset.Zero)
        // A pull always ends in a release: cancelled part way (the page leaves, the gesture is taken away), it springs
        // back rather than staying pulled down.
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                // Read the move before consuming it: a consumed change reports no position change.
                val dy = change.positionChange().y
                fingerY += dy
                tracker.addPosition(change.uptimeMillis, Offset(0f, fingerY))
                if (!change.pressed) {
                    // The lift can still carry the last stretch of the move.
                    if (pulling) offset = PullToClose.dragged(offset, dy)
                    break
                }
                if (!pulling) {
                    if (change.isConsumed) return@awaitEachGesture
                    travel += dy
                    when {
                        travel > slop -> {
                            pulling = true
                            offset = PullToClose.dragged(0f, travel - slop)
                        }
                        travel < -slop -> return@awaitEachGesture
                        else -> continue
                    }
                } else {
                    offset = PullToClose.dragged(offset, dy)
                }
                change.consume()
                pull.move(PullToClose.progress(offset, height))
            }
            if (pulling) close = PullToClose.shouldClose(offset, tracker.calculateVelocity().y, height, flingVelocity)
        } finally {
            if (pulling) pull.release(close)
        }
    }
}

@Composable
private fun TopBar(translucent: TranslucentBackdrop, tint: Color, onBack: () -> Unit, onMore: (() -> Unit)?, select: PageSelection?) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(TopBarHeight).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val selection = select?.selection
        if (selection != null && selection.active) {
            // Selecting: Select All / Deselect All and Done, as iOS swaps its bar buttons.
            val keys = select.shown.map { it.first }
            val all = selection.state.allSelected(keys)
            TranslucentCapsule(translucent, tint, stringResource(if (all) R.string.picker_deselect_all else R.string.picker_select_all)) {
                selection.toggleAll(keys)
            }
            Spacer(Modifier.weight(1f))
            TranslucentCapsule(translucent, tint, stringResource(R.string.select_done), onClick = selection::exit)
            return@Row
        }
        TranslucentCircle(translucent, tint, SonoraIcons.ChevronLeft, stringResource(io.sonora.core.designsystem.R.string.ds_back), onBack)
        Spacer(Modifier.weight(1f))
        if (selection != null && select.shown.isNotEmpty()) {
            TranslucentCapsule(translucent, tint, stringResource(R.string.select_action)) { selection.start() }
            Spacer(Modifier.width(8.dp))
        }
        if (onMore != null) TranslucentCircle(translucent, tint, SonoraIcons.More, stringResource(R.string.playlist_more), onMore)
    }
}

@Composable
private fun TranslucentCapsule(translucent: TranslucentBackdrop, tint: Color, label: String, onClick: () -> Unit) {
    // The page's hue washes the translucent surface, as TranslucentCircle's does; the shared pill reflects the label
    // under the surface.
    TranslucentPill(
        label,
        onClick,
        height = 44.dp,
        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        darkTheme = true,
        backdrop = translucent,
        tinted = false,
        wash = { tint.copy(alpha = 0.6f) },
    )
}

@Composable
private fun TranslucentCircle(translucent: TranslucentBackdrop, tint: Color, icon: ImageVector, description: String, onClick: () -> Unit) {
    TranslucentSurface(Modifier.size(44.dp), shape = CircleShape, elevation = 0.dp, darkTheme = true, backdrop = translucent, tinted = false) {
        Box(
            // The page's hue over the translucent surface, as iOS tints its bar buttons on a coloured page.
            Modifier.fillMaxSize().clip(CircleShape).background(tint.copy(alpha = 0.6f))
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = LocalTranslucentContentColors.current.label)
        }
    }
}

/** Light status bar icons while the dark tinted page is shown; the previous look comes back when it leaves. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (wasLight != null) controller.isAppearanceLightStatusBars = wasLight }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Artwork, bold title, a tappable subtitle and the small metadata line, centred. */
@Composable
internal fun PageHeader(
    artworkUri: String?,
    title: String,
    subtitle: String?,
    metadata: String?,
    colors: PageColors,
    onSubtitle: (() -> Unit)?,
    titleStyleLarge: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        val shape = RoundedCornerShape(10.dp)
        Artwork(
            artworkUri,
            Modifier.fillMaxWidth(0.68f).widthIn(max = 320.dp).aspectRatio(1f).shadow(18.dp, shape),
            shape,
            contentDescription = title,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = if (titleStyleLarge) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            val tap = if (onSubtitle != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onSubtitle) else Modifier
            Text(
                subtitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = tap.padding(horizontal = 4.dp),
            )
        }
        if (metadata != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                metadata,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Small round Shuffle, wide white Play capsule (one line at any font scale, taller when a large font needs it), small
 * round Add to Playlist. Centred under the cover; [beside] (the large-screen header with the cover on its side) puts them
 * at the start of the titles' column instead.
 */
@Composable
internal fun PlayButtonsRow(
    colors: PageColors,
    enabled: Boolean,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    beside: Boolean = false,
) {
    Row(
        if (beside) Modifier.fillMaxWidth().padding(top = 16.dp) else Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, if (beside) Alignment.Start else Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(SonoraIcons.Shuffle, stringResource(R.string.library_shuffle), colors.buttonFill, enabled, onShuffle)
        Box(Modifier.weight(1f, fill = false).widthIn(max = 240.dp)) {
            PlayCapsule(enabled, onPlay)
        }
        RoundButton(SonoraIcons.Add, stringResource(R.string.playlist_picker_title), colors.buttonFill, enabled, onAdd)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, fill: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(RoundButtonSize).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

private val RoundButtonSize = 52.dp

/**
 * The white Play capsule: the triangle centred in a circle at its leading end, the label centred in the whole capsule
 * unless that would run it into the triangle, in which case it starts just after it. As tall as the round buttons, or
 * taller when the label needs it (a large font scale), so the label is never clipped.
 */
@Composable
private fun PlayCapsule(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.library_play)
    Box(
        Modifier.fillMaxWidth().heightIn(min = RoundButtonSize).clip(CapsuleShape).background(Color.White)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.size(RoundButtonSize).align(Alignment.CenterStart), contentAlignment = Alignment.Center) {
            Icon(SonoraIcons.Play, contentDescription = null, tint = PlayLabel, modifier = Modifier.size(22.dp))
        }
        val style = MaterialTheme.typography.titleLarge
        Layout(
            content = {
                BasicText(
                    label,
                    style = style.copy(color = PlayLabel, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val minStart = PlayLabelMinStart.roundToPx()
            val room = (constraints.maxWidth - minStart - PlayLabelEndPadding.roundToPx()).coerceAtLeast(0)
            val text = measurables.single().measure(Constraints(maxWidth = room))
            val height = playCapsuleHeight(text.height, RoundButtonSize.roundToPx(), PlayLabelVerticalPadding.roundToPx())
            layout(constraints.maxWidth, height) {
                text.place(playLabelStart(constraints.maxWidth, text.width, minStart), (height - text.height) / 2)
            }
        }
    }
}

/** Where the Play label starts: centred in the [capsuleWidth], but never before [minStart] (just after the triangle). */
internal fun playLabelStart(capsuleWidth: Int, labelWidth: Int, minStart: Int): Int =
    maxOf((capsuleWidth - labelWidth) / 2, minStart)

/** The capsule's height: [minHeight] (the round buttons'), or the [labelHeight] with [padding] above and below. */
internal fun playCapsuleHeight(labelHeight: Int, minHeight: Int, padding: Int): Int = maxOf(minHeight, labelHeight + 2 * padding)

private val PlayLabelEndPadding = 16.dp

/** The least room above and below the label in a capsule grown for it. */
private val PlayLabelVerticalPadding = 8.dp

/** The triangle ends 37 dp in (22 dp icon centred in the 52 dp circle); the label keeps 6 dp clear of it. */
private val PlayLabelMinStart = 43.dp

/** Bold section title, optionally tappable with a chevron (iOS "More by … ›"). */
@Composable
internal fun SectionTitle(text: String, colors: PageColors, onClick: (() -> Unit)? = null) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(tap.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.mutedText, modifier = Modifier.size(22.dp))
        }
    }
}

/** A horizontal row of album cards: cover, title and year. */
@Composable
internal fun AlbumCardRow(albums: List<Album>, colors: PageColors, onOpen: (Album) -> Unit, onMenu: (Album) -> Unit) {
    // On a large window the cards start where the page's content does and still scroll across the whole width.
    val inset = LocalContentArea.current.inset
    LazyRow(
        contentPadding = PaddingValues(start = 20.dp + inset, end = 20.dp + inset),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(albums, key = { it.id }) { album -> AlbumCard(album, colors, CardWidth, { onOpen(album) }, { onMenu(album) }) }
    }
}

private val CardWidth = 150.dp

@Composable
private fun AlbumCard(album: Album, colors: PageColors, width: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.width(width).pressable(onClick, onLongClick)) {
        Artwork(album.artworkUri, Modifier.size(width), RoundedCornerShape(8.dp), contentDescription = null)
        Spacer(Modifier.height(6.dp))
        Text(album.title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.year?.let {
            Text("$it", style = MaterialTheme.typography.bodyMedium, color = colors.mutedText, maxLines = 1)
        }
    }
}

/**
 * The header of an album's songs on the artist page: small artwork, title and year, and a chevron. Tap opens the album
 * like its card in the carousel; long-press opens the album's menu.
 */
@Composable
internal fun AlbumSectionHeader(album: Album, colors: PageColors, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onOpen, onMenu).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(album.artworkUri, Modifier.size(56.dp), RoundedCornerShape(6.dp), contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            album.year?.let {
                Text("$it", style = MaterialTheme.typography.bodyMedium, color = colors.mutedText, maxLines = 1)
            }
        }
        Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.mutedText, modifier = Modifier.size(22.dp))
    }
}

/** A song in the artist page's Singles: its own artwork, title and the release's year; tap plays, long-press opens the menu. */
@Composable
internal fun SingleSongRow(
    title: String,
    artworkUri: String?,
    year: Int?,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.pressable(onClick, onLongClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artworkUri, Modifier.size(48.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (year != null) {
                    Text("$year", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (pinned) PinnedGlyph(Modifier.padding(start = 8.dp))
            if (selected != null) {
                Spacer(Modifier.width(12.dp))
                SelectionMark(selected)
            }
        }
        InsetDivider(start = 80.dp)
    }
}

/** Albums newest first: by year (unknown last), then by date added. */
internal fun newestFirst(albums: List<Album>): List<Album> =
    albums.sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenByDescending { it.dateAddedSec })
