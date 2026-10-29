package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.beats.BarPhaseOptions
import io.sonora.tools.calibration.MarkdownTable
import io.sonora.tools.calibration.MarkdownTable.number

/**
 * The D1.29 bar phase study: re-scores the cached captures under variants of [BarPhaseOptions] (one setting at a time
 * from the defaults) and against the grid fit's own vote ([CalibrationConfig.PRE_D1_29]), so the default can be judged on
 * its numbers and on how much of the result depends on the exact values. Beats are the same under every variant (only
 * the bar position of the grid moves), so the beat scores are not repeated here.
 *
 * "Accurate-beat" tracks are those with a beat F-measure above 0.9: there the grid is right and a wrong bar phase is the
 * bar phase's own error. "Right" means the reference's bars start on our bars (no shift needed).
 */
object BarPhaseSweep {

    /** One variant's numbers. [fixed] and [broken] count accurate-beat tracks whose bar phase became right or wrong against the baseline. */
    class Score(
        val name: String,
        val downbeatAccuracy: Double,
        val barAgreement: Double,
        val accurateTracks: Int,
        val rightPhase: Int,
        val fixed: Int,
        val broken: Int,
    )

    /** A fourth of the tracks by confidence: how many of them had the right bar phase. */
    class Quartile(val tracks: Int, val right: Int, val minConfidence: Double, val maxConfidence: Double)

    private val DEFAULT = BarPhaseOptions()

    /** The grid fit's own vote, the default, then each setting varied alone (the weight, the window, the level, the off-beat limit). */
    val variants: List<CalibrationConfig> = buildList {
        add(CalibrationConfig.PRE_D1_29)
        add(CalibrationConfig.BASELINE)
        for (weight in listOf(0.1, 0.2, 0.5, 1.0)) add(named("intro weight $weight", BarPhaseOptions(introWeight = weight)))
        for (window in listOf(30.0, 60.0, 200.0, 1_000.0)) add(named("intro window $window ms", BarPhaseOptions(introWindowMs = window)))
        for (level in listOf(0.1, 0.3, 0.5)) add(named("intro level $level", BarPhaseOptions(introLevelFraction = level)))
        add(named("intro off-beat 0.5", BarPhaseOptions(introOffBeatFraction = 0.5)))
        add(named("intro off-beat 0.1", BarPhaseOptions(introOffBeatFraction = 0.1)))
    }

    private fun named(name: String, options: BarPhaseOptions) = CalibrationConfig.BASELINE.copy(name = name, barPhase = options)

    /** [results] scored against [baseline] (same tracks in the same order, the grid fit's own vote). */
    fun score(name: String, results: List<TrackResult>, baseline: List<TrackResult>): Score {
        require(results.size == baseline.size) { "Results and baseline must cover the same tracks: ${results.size} vs ${baseline.size}" }
        val aggregates = CalibrationReport.aggregates(results)
        var fixed = 0
        var broken = 0
        for ((now, before) in results.zip(baseline)) {
            if (now.beat.fMeasure <= ACCURATE_F) continue
            val right = now.barPhase.bestShift == 0
            val wasRight = before.barPhase.bestShift == 0
            if (right && !wasRight) fixed++
            if (!right && wasRight) broken++
        }
        return Score(name, aggregates.downbeatAccuracy, aggregates.barAgreement, aggregates.beatAccurateTracks, aggregates.beatAccurateRightPhase, fixed, broken)
    }

    /** [rows] (confidence, right) sorted by confidence and cut into four fourths. */
    fun confidenceQuartiles(rows: List<Pair<Double, Boolean>>): List<Quartile> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedBy { it.first }
        return (0 until QUARTILES).map { q ->
            val part = sorted.subList(q * sorted.size / QUARTILES, (q + 1) * sorted.size / QUARTILES)
            Quartile(part.size, part.count { it.second }, part.firstOrNull()?.first ?: Double.NaN, part.lastOrNull()?.first ?: Double.NaN)
        }
    }

    fun markdown(scores: List<Score>, confidence: List<Pair<Double, Boolean>>): String = buildString {
        appendLine("## Bar phase")
        appendLine()
        appendLine(
            "Each row changes one setting of the bar phase from the default; beats and tempo are the same in every row. " +
                "`right` counts the tracks with beat F above 0.9 whose bars start where the reference's do; `fixed` and `broken` " +
                "are against the pre-D1.29 row.",
        )
        appendLine()
        append(
            MarkdownTable.render(
                listOf("variant", "downbeat", "bar agree", "right (F>0.9)", "of", "fixed", "broken"),
                scores.map {
                    listOf(
                        it.name, number(it.downbeatAccuracy, 3), number(it.barAgreement, 3), it.rightPhase.toString(),
                        it.accurateTracks.toString(), it.fixed.toString(), it.broken.toString(),
                    )
                },
                rightAligned = (1..6).toSet(),
            ),
        )
        appendLine()
        appendLine("## Confidence")
        appendLine()
        appendLine("Tracks with accurate beats cut into fourths by the confidence of the default bar phase, and how many had the right phase.")
        appendLine()
        append(
            MarkdownTable.render(
                listOf("fourth", "confidence", "tracks", "right"),
                confidenceQuartiles(confidence).mapIndexed { i, q ->
                    listOf("${i + 1}", "${number(q.minConfidence, 2)} to ${number(q.maxConfidence, 2)}", q.tracks.toString(), q.right.toString())
                },
                rightAligned = setOf(2, 3),
            ),
        )
    }

    private const val ACCURATE_F = 0.9
    private const val QUARTILES = 4
}
