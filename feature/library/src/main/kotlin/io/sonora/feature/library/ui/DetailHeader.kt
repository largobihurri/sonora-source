package io.sonora.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.designsystem.layout.LocalContentArea
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.layout.contentWidth

/** The header arrangement for the width the page's content has (see [DetailHeaderLayout]). */
@Composable
internal fun rememberDetailHeaderLayout(): DetailHeaderLayout {
    val large = LocalWindowClass.current.isLarge
    val width = LocalContentArea.current.width?.value
    return remember(large, width) { DetailHeaderLayout.of(large, width) }
}

/**
 * The header of a page with its cover on the leading side: a square [cover] [coverDp] wide and, beside it, the
 * [details] (titles and the Play / Shuffle buttons), bottom-aligned with the cover as on the iPad's Music pages.
 */
@Composable
internal fun SideHeader(coverDp: Float, cover: @Composable () -> Unit, details: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.size(coverDp.dp)) { cover() }
        Column(Modifier.weight(1f), content = details)
    }
}

/**
 * One block of a tinted page (a header, a section title, a footer): kept to the page's table width and centred on a large
 * window, so it lines up with the song rows; a phone's is plain full width.
 */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().contentWidth(ContentWidth.TABLE_DP.dp), content = content)
}

/** The width a song row of a tinted page keeps: the table width, centred, on a large window. */
@Composable
internal fun Modifier.pageRowWidth(): Modifier = contentWidth(ContentWidth.TABLE_DP.dp)

/** The square cover of a [SideHeader], with the soft shadow of the phone header's. */
@Composable
internal fun SideCover(artworkUri: String?, coverDp: Float, description: String) {
    val shape = RoundedCornerShape(10.dp)
    Artwork(artworkUri, Modifier.size(coverDp.dp).shadow(18.dp, shape), shape, contentDescription = description)
}

/** The page's title in a [SideHeader], bold white on the tinted page; [large] for an artist's name. */
@Composable
internal fun SideTitle(text: String, large: Boolean = false) {
    Text(
        text,
        style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The artist under an album's title in a [SideHeader]; tapping it ([onClick]) opens the artist page. */
@Composable
internal fun SideSubtitle(text: String, onClick: (() -> Unit)?) {
    val tap = if (onClick != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Normal,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = tap,
    )
}

/** The genre and year ([primary]) and the format and quality ([secondary]) under the titles of a [SideHeader]. */
@Composable
internal fun SideMetadata(primary: String?, secondary: String?, colors: PageColors) {
    if (primary != null) {
        Text(
            primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.secondaryText,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (secondary != null) {
        Text(secondary, style = MaterialTheme.typography.bodySmall, color = colors.mutedText, modifier = Modifier.padding(top = 2.dp))
    }
}
