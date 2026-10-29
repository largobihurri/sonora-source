package io.sonora.feature.dub.controls

import androidx.annotation.StringRes
import io.sonora.feature.dub.R

/**
 * The five ways of the DUB preamp (design §10.1) with their default corners: adjacent corners are equal, so the
 * defaults sum flat. [label] is the strip's name, [number] the small kill switch's.
 */
enum class DubWay(val number: Int, val defaultHighPassHz: Float, val defaultLowPassHz: Float, @StringRes val label: Int) {
    SUB(1, CornerMapping.MIN_HZ, 80f, R.string.dub_way_sub),
    BASS(2, 80f, 250f, R.string.dub_way_bass),
    LOW_MID(3, 250f, 1_200f, R.string.dub_way_low_mid),
    HIGH_MID(4, 1_200f, 5_000f, R.string.dub_way_high_mid),
    TOP(5, 5_000f, CornerMapping.MAX_HZ, R.string.dub_way_top),
}
