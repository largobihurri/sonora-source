package io.sonora.feature.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.lyrics.Lyrics

/**
 * The lyrics panel that replaces the artwork. Synced lyrics highlight the line playing at [positionMs] (read in the panel, not by the screen), keep it in
 * view (unless the user is scrolling) and seek to a line when it is tapped; plain lyrics scroll as text. Without
 * lyrics the panel offers the online search, which only runs when the user taps it; a build without one ([onSearch]
 * `null`) just says the song has no lyrics.
 */
@Composable
internal fun LyricsView(
    panel: LyricsPanel,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onSearch: (() -> Unit)?,
    onRemoveDownloaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (panel) {
            LyricsPanel.Hidden -> Unit
            LyricsPanel.Loading -> Spinner()
            LyricsPanel.Searching -> Message(stringResource(R.string.now_playing_lyrics_searching)) { Spinner() }
            LyricsPanel.Missing -> if (onSearch == null) {
                Message(stringResource(R.string.now_playing_no_lyrics)) {}
            } else {
                Message(
                    stringResource(R.string.now_playing_no_lyrics),
                    note = stringResource(R.string.now_playing_lyrics_search_note),
                ) {
                    CapsuleButton(stringResource(R.string.now_playing_lyrics_search), onSearch)
                }
            }
            LyricsPanel.NotFound -> Message(stringResource(R.string.now_playing_lyrics_not_found)) {
                onSearch?.let { CapsuleButton(stringResource(R.string.now_playing_lyrics_search_again), it) }
            }
            LyricsPanel.Failed -> Message(stringResource(R.string.now_playing_lyrics_failed)) {
                onSearch?.let { CapsuleButton(stringResource(R.string.now_playing_lyrics_retry), it) }
            }
            is LyricsPanel.Showing -> {
                val footer: (@Composable () -> Unit)? = if (panel.downloaded) {
                    { DownloadedFooter(onRemoveDownloaded) }
                } else {
                    null
                }
                when (val lyrics = panel.lyrics) {
                    is Lyrics.Synced -> SyncedLyrics(lyrics, positionMs, onSeek, footer)
                    is Lyrics.Plain -> PlainLyrics(lyrics.text, footer)
                    Lyrics.Instrumental -> Message(stringResource(R.string.now_playing_lyrics_instrumental)) { footer?.invoke() }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics.Synced, positionMs: () -> Long, onSeek: (Long) -> Unit, footer: (@Composable () -> Unit)?) {
    // Recomposes when the line changes, not with each position tick.
    val active by remember(lyrics, positionMs) { derivedStateOf { lyrics.activeLineIndex(positionMs()) } }
    val listState = rememberLazyListState()
    LaunchedEffect(lyrics, active) {
        // The user's own scrolling wins until the next line starts.
        if (!listState.isScrollInProgress) listState.animateScrollToItem(active.coerceAtLeast(0))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The playing line sits a third of the way down, with room to scroll the last lines up to it.
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight * 2 / 3),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val current = index == active
                val color by animateColorAsState(if (current) Color.White else Color.White.copy(alpha = 0.35f), label = "lyricColor")
                val scale by animateFloatAsState(if (current) 1f else 0.96f, label = "lyricScale")
                Text(
                    text = line.text.ifEmpty { "♪" },
                    color = color,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LineShape)
                        .clickable(role = Role.Button) { onSeek(line.timeMs) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            if (footer != null) item { footer() }
        }
    }
}

private val LineShape = RoundedCornerShape(10.dp)

/** A small progress spinner; still (an empty place of its size) in an arrangement kept hidden for the next turn. */
@Composable
private fun Spinner() {
    if (LocalArrangementRole.current() == ArrangementRole.Hidden) {
        Spacer(Modifier.size(28.dp))
    } else {
        CircularProgressIndicator(color = Secondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun PlainLyrics(text: String, footer: (@Composable () -> Unit)?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Text(text, color = Color.White, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        footer?.invoke()
    }
}

/** Where downloaded lyrics come from, and the way to remove them. */
@Composable
private fun DownloadedFooter(onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.now_playing_lyrics_source), color = Secondary, fontSize = 13.sp)
        Text(
            stringResource(R.string.now_playing_lyrics_remove),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CapsuleShape).clickable(role = Role.Button, onClick = onRemove).padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun Message(text: String, note: String? = null, action: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Secondary, fontSize = 17.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        action()
        if (note != null) {
            Spacer(Modifier.height(12.dp))
            Text(note, color = Secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A translucent capsule sampling the artwork backdrop, a bar button over imagery: 44 dp tall, untinted and always
 * dark, its label in the translucent label colour. Large font sizes grow it taller; a label too long for the width
 * ends in an ellipsis.
 */
@Composable
private fun CapsuleButton(text: String, onClick: () -> Unit) {
    TranslucentSurface(shape = CapsuleShape, elevation = 0.dp, darkTheme = true, tinted = false) {
        // Fills the surface, so the label and its touch area are centred and never hug the top edge.
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(CapsuleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = LocalTranslucentContentColors.current.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
