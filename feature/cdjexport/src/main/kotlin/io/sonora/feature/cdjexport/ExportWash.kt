package io.sonora.feature.cdjexport

import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.ColorMath
import io.sonora.core.domain.model.Track

/**
 * One soft patch of colour in the Export tab's wash. Placed and sized as fractions of the tab: [centerX] of its width,
 * [centerY] of its height, [radius] of the longer side. [argb] is opaque; the patch is [alpha] strong at the centre and
 * fades to nothing at its edge.
 */
internal data class WashBlob(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val argb: Int,
    val alpha: Float,
)

/** What tints the Export tab, decided without Compose so it can be tested. */
internal object ExportWash {

    /** The track whose cover tints the tab: the first one listed. `null` for an empty list. */
    fun sourceUri(tracks: List<PickedTrack>): String? = tracks.firstOrNull()?.uri

    /**
     * The artwork the library shows for each of the listed track [uris] it knows a cover for, by track URI. A track the
     * library does not index, or has no cover for, is left out (its row shows the placeholder).
     */
    fun artworkByUri(uris: Collection<String>, library: List<Track>): Map<String, String> {
        if (uris.isEmpty()) return emptyMap()
        val wanted = uris.toHashSet()
        val found = HashMap<String, String>()
        for (track in library) {
            if (track.uri !in wanted) continue
            val artwork = track.artworkUri?.takeIf { it.isNotBlank() } ?: continue
            found.putIfAbsent(track.uri, artwork)
        }
        return found
    }

    /**
     * The palette the wash is drawn from: the [cover]'s once it is read, else the app [accent]'s (neutral grey when
     * there is none), so a list without a usable cover still has a calm colour of the app's own. [hasCover] says the
     * first track has a cover to read; without one the [cover] palette (the previous track's, or the neutral one the
     * artwork reader gives a missing artwork) is not used.
     */
    fun palette(cover: ArtworkPalette?, hasCover: Boolean, accent: Int?): ArtworkPalette =
        if (hasCover && cover != null) cover else ArtworkPalette.from(accent)

    /**
     * The patches of the wash for [palette]: a lighter one top-left, where the translucency's light comes from, a deeper one
     * on the right and a soft one low on the left, in the palette's own tones. Fainter in a light theme, so the page
     * stays light. Each tone is first moved just far enough for the page's text to keep [LABEL_CONTRAST] over it:
     * darkened for white text, lightened for black text (the palette's tones suit a dark page, and a near-black cover
     * gives tones too dark for a light one). A patch only mixes its tone with the page, so the text keeps its contrast
     * wherever patches overlap.
     */
    fun blobs(palette: ArtworkPalette, dark: Boolean): List<WashBlob> {
        val text = if (dark) WHITE else BLACK
        val tones = listOf(palette.buttonFill, palette.background, palette.divider)
            .map { ColorMath.ensureContrast(it, listOf(text), LABEL_CONTRAST) }
        val strength = if (dark) DARK_STRENGTH else LIGHT_STRENGTH
        return listOf(
            WashBlob(centerX = 0.10f, centerY = 0.10f, radius = 0.80f, argb = tones[0], alpha = strength[0]),
            WashBlob(centerX = 0.95f, centerY = 0.50f, radius = 0.70f, argb = tones[1], alpha = strength[1]),
            WashBlob(centerX = 0.15f, centerY = 0.95f, radius = 0.75f, argb = tones[2], alpha = strength[2]),
        )
    }

    /** The page's text must keep this contrast over a patch's tone: AA with room to spare. */
    private const val LABEL_CONTRAST = 5.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private val DARK_STRENGTH = floatArrayOf(0.60f, 0.75f, 0.45f)
    private val LIGHT_STRENGTH = floatArrayOf(0.30f, 0.36f, 0.22f)
}
