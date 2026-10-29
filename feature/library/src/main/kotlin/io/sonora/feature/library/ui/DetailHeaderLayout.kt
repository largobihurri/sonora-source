package io.sonora.feature.library.ui

import io.sonora.core.domain.model.Track

/**
 * How the header of an album, artist or playlist page is arranged. A phone's is [Centred] (cover above, titles and
 * buttons under it, centred). A large screen with room for two blocks puts the cover on the leading side with the
 * titles and the Play / Shuffle buttons [Beside] it, like the Apple Music pages on the iPad, with the tracks below.
 *
 * The choice follows the width the page's content actually has, not the window's: a tablet held upright with a sidebar
 * open leaves a narrow column, where the cover stays above the titles.
 */
internal sealed interface DetailHeaderLayout {
    /** Cover above the titles, all centred: the phone header, also a large screen's narrow column. */
    data object Centred : DetailHeaderLayout

    /** Cover [coverDp] wide on the leading side, titles and buttons beside it. */
    data class Beside(val coverDp: Float) : DetailHeaderLayout

    companion object {
        /** The narrowest content that fits a cover and the titles side by side. */
        const val BESIDE_MIN_DP = 560

        const val MIN_COVER_DP = 200f
        const val MAX_COVER_DP = 340f

        /** The share of the content width the cover takes when it sits beside the titles. */
        private const val COVER_SHARE = 0.34f

        /** The header of a page whose content is [widthDp] wide (`null` outside a capped column, as on a phone). */
        fun of(isLarge: Boolean, widthDp: Float?): DetailHeaderLayout =
            if (!isLarge || widthDp == null || widthDp < BESIDE_MIN_DP) {
                Centred
            } else {
                Beside((widthDp * COVER_SHARE).coerceIn(MIN_COVER_DP, MAX_COVER_DP))
            }
    }
}

/** The genre most of [tracks] share (the first song's wins a tie); blank and missing genres are ignored. */
internal fun albumGenre(tracks: List<Track>): String? {
    val genres = tracks.mapNotNull { it.genre?.trim()?.takeIf(String::isNotEmpty) }
    val counts = genres.groupingBy { it }.eachCount()
    val best = counts.values.maxOrNull() ?: return null
    return genres.first { counts.getValue(it) == best }
}
