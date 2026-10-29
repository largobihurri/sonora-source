package io.sonora.feature.nowplaying

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelActionRow
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentPanelHeader
import io.sonora.core.designsystem.component.TranslucentPanelValueRow
import io.sonora.core.designsystem.component.scrollEdgeFade
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.model.Track

/**
 * "File Info" as a floating translucent panel over the blurred app, like the long-press menu: the song's header, a
 * spinner while the file is read, then Edit Info and the file's sections (or the error). [track] is the song asked
 * about, shown in the header while it loads; once read, Edit Info opens the tag editor for the track inspected
 * ([onEdit]). Tapping outside and Back call [onDismiss].
 */
@Composable
internal fun FileInfoPanel(sheet: FileInfoSheet, track: Track?, onDismiss: () -> Unit, onEdit: (Track) -> Unit) {
    val strings = rememberStrings()
    TranslucentPanel(stringResource(R.string.now_playing_file_info), onDismiss) {
        val label = LocalTranslucentContentColors.current.label
        Column(Modifier.animateContentSize()) {
            val shown = (sheet as? FileInfoSheet.Loaded)?.track ?: track
            shown?.let { TranslucentPanelHeader(it.artworkUri, it.title, FileInfoText.headerSubtitle(it)) }
            when (sheet) {
                FileInfoSheet.Hidden -> Unit
                FileInfoSheet.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                    CircularProgressIndicator(color = label)
                }
                FileInfoSheet.Unreadable -> Text(
                    stringResource(R.string.file_info_read_failed),
                    fontSize = 16.sp,
                    color = label.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                )
                is FileInfoSheet.Loaded -> {
                    val scroll = rememberScrollState()
                    // Rows scrolled under the header or the panel's bottom fade out rather than being cut.
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .scrollEdgeFade(scroll)
                            .verticalScroll(scroll)
                            .padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        TranslucentPanelGroup(title = null) {
                            TranslucentPanelActionRow(stringResource(R.string.tag_edit_title), EditIcon) { onEdit(sheet.track) }
                        }
                        for (section in FileInfoText.sections(sheet.info, sheet.decoded, strings)) {
                            TranslucentPanelGroup(section.title, section.footer) {
                                section.rows.forEachIndexed { index, row ->
                                    if (index > 0) TranslucentPanelDivider()
                                    TranslucentPanelValueRow(row.label, row.value, selectable = row.copyable)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A pencil, for Edit Info. */
private val EditIcon: ImageVector by lazy {
    ImageVector.Builder("Edit", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34" +
                    "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}
