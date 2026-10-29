package io.sonora.feature.nowplaying

import io.sonora.core.designsystem.text.Strings
import io.sonora.core.djmodel.AnalysisStatus
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor

/** What the DJ chip in Now Playing's header shows about the analysis of the song playing. */
internal sealed interface DjChip {
    /** Not analysed yet (or not reported yet): the analysis will get to it. */
    data object Waiting : DjChip

    /** Being analysed, [percent] of it done (0..99: only [Analysed] means finished). */
    data class Analysing(val percent: Int) : DjChip

    data object Analysed : DjChip

    data object Failed : DjChip
}

/** How the DJ chip reads: "DJ", "DJ 42%" while analysing, "DJ !" when the analysis failed, and its spoken description. */
internal object DjStatusText {

    private const val NAME = "DJ"

    /** The chip for the playing song's [status]; none reported (null) or not analysed reads as waiting. */
    fun chip(status: AnalysisStatus?): DjChip = when (status) {
        null, AnalysisStatus.NotAnalysed, AnalysisStatus.Queued -> DjChip.Waiting
        is AnalysisStatus.Analysing -> DjChip.Analysing(percent(status.progress))
        AnalysisStatus.Analysed -> DjChip.Analysed
        is AnalysisStatus.Failed -> DjChip.Failed
    }

    /** The progress floored to a whole percent within 0..99, so the chip never claims more than is done. */
    private fun percent(progress: Float): Int =
        if (progress.isNaN()) 0 else floor(progress * 100.0).toInt().coerceIn(0, 99)

    /** The text on the chip. The percentage follows [locale]'s own style ("42%", "42 %", Basque "% 42"). */
    fun label(chip: DjChip, locale: Locale): String = when (chip) {
        DjChip.Waiting, DjChip.Analysed -> NAME
        DjChip.Failed -> "$NAME !"
        is DjChip.Analysing -> "$NAME ${percentage(chip.percent, locale)}"
    }

    /** What a screen reader says for the chip. */
    fun description(chip: DjChip, strings: Strings): String = when (chip) {
        DjChip.Waiting -> strings.get(R.string.dj_chip_waiting)
        DjChip.Analysed -> strings.get(R.string.dj_chip_analysed)
        DjChip.Failed -> strings.get(R.string.dj_chip_failed)
        is DjChip.Analysing -> strings.get(R.string.dj_chip_analysing, percentage(chip.percent, strings.locale))
    }

    private fun percentage(percent: Int, locale: Locale): String =
        NumberFormat.getPercentInstance(locale).format(percent / 100.0)
}
