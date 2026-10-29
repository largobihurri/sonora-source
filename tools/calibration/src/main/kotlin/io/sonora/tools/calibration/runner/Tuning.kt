package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.beats.GridFitOptions
import io.sonora.core.djengine.beats.TempoPrior
import io.sonora.core.djengine.key.FrameWeighting
import io.sonora.core.djengine.key.KeyProfile
import io.sonora.tools.calibration.MarkdownTable
import io.sonora.tools.calibration.MarkdownTable.number
import io.sonora.tools.calibration.Stats
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tuning round: re-scores the cached captures under variations of the analysis parameters, one factor at a time
 * from the defaults, so each default can be judged on its own numbers. Grid variants (tempo prior, grid-fit and
 * variable-tempo tolerances, phase refinement, a constant bias) are judged on beats and tempo; key variants (profile,
 * frame weighting) on the MIREX score; level-curve exponents on the waveform correlations. Nothing is decoded again.
 */
class Tuning(private val pairs: List<Pair<TrackCapture, ReferenceTrack>>, private val log: (String) -> Unit) {

    fun writeReport(out: Path, label: String): List<Path> {
        val text = buildString {
            appendLine("# D1.20 tuning sweep: $label")
            appendLine()
            appendLine("${pairs.size} tracks, cached features re-scored; each row changes one thing from the baseline (the current defaults).")
            appendLine()
            appendLine("## Grid: tempo prior, fit tolerances, phase refinement, bias")
            appendLine()
            val gridRows = GRID_VARIANTS.map { config ->
                log("tuning ${config.name}")
                config.name to CalibrationReport.aggregates(score(config, waveform = false))
            }
            append(CalibrationReport.comparison(gridRows))
            appendLine()

            appendLine("## Variable-tempo tracks (reference has tempo changes)")
            appendLine()
            val variable = pairs.filter { it.second.grid.isVariable }
            if (variable.isEmpty()) {
                appendLine("None in this subset.")
            } else {
                val rows = VARIABLE_VARIANTS.map { config ->
                    val results = variable.map { (capture, reference) ->
                        Evaluator.evaluate(capture, reference, config, 0.0, Double.NaN, includeWaveform = false)
                    }
                    listOf(config.name) + results.map { "${number(it.beat.fMeasure, 3)} (${it.segments})" }
                }
                append(
                    MarkdownTable.render(
                        listOf("config") + variable.map { it.first.path.substringAfterLast('/').take(NAME_CHARS) },
                        rows,
                    ),
                )
                appendLine()
                appendLine("Cells: F-measure (segments). Reference segments: ${variable.joinToString { "${it.second.grid.beats.size} beats" }}.")
            }
            appendLine()

            appendLine("## Key: profile and frame weighting")
            appendLine()
            val keyRows = KEY_VARIANTS.map { config -> config.name to CalibrationReport.aggregates(score(config, waveform = false)) }
            append(
                MarkdownTable.render(
                    listOf("config", "keyed tracks", "MIREX", "exact"),
                    keyRows.map { (name, a) -> listOf(name, a.keyTracks.toString(), number(a.keyMirex, 3), number(a.keyExact, 3)) },
                    rightAligned = setOf(1, 2, 3),
                ),
            )
            appendLine()

            appendLine("## Waveform: level-curve exponent")
            appendLine()
            appendLine("Our linear band bytes reshaped to `255 * (v / 255) ^ e` before correlating (a `BandLevelCurve` exponent).")
            appendLine()
            val waveformRows = EXPONENTS.map { exponent ->
                val results = score(CalibrationConfig.BASELINE.copy(name = "exponent $exponent", levelExponent = exponent), waveform = true)
                val votes = CalibrationReport.bandOrderVotes(results) { it.bandOrder }
                val best = votes.firstOrNull()
                val pwv5 = Stats.mean(results.mapNotNull { it.waveformCorrelations["pwv5.height~peak"] }.toDoubleArray())
                listOf(
                    number(exponent, 2), best?.fileOrder ?: "-", number(best?.meanCorrelation ?: Double.NaN, 3),
                    perBand(results, 0), perBand(results, 1), perBand(results, 2), number(pwv5, 3),
                )
            }
            append(
                MarkdownTable.render(
                    listOf("exponent", "PWV7 order", "PWV7 mean r", "low r", "mid r", "high r", "PWV5 height~peak r"),
                    waveformRows,
                    rightAligned = setOf(0, 2, 3, 4, 5, 6),
                ),
            )
        }
        val file = out.resolve("$label-tuning.md")
        Files.writeString(file, text)
        return listOf(file)
    }

    private fun score(config: CalibrationConfig, waveform: Boolean): List<TrackResult> = pairs.map { (capture, reference) ->
        Evaluator.evaluate(capture, reference, config, capture.wallMs / MS_PER_SECOND, capture.temperatureC, includeWaveform = waveform)
    }

    /** Mean correlation of our band [band] with the stored plane the identity-free best global order gives it. */
    private fun perBand(results: List<TrackResult>, band: Int): String {
        val votes = CalibrationReport.bandOrderVotes(results) { it.bandOrder }
        val bestOrder = votes.firstOrNull()?.fileOrder ?: return "-"
        val values = results.mapNotNull { r ->
            r.bandOrder?.ranked?.firstOrNull { CalibrationReport.fileOrder(it.assignment) == bestOrder }?.correlations?.get(band)
        }
        return number(Stats.mean(values.toDoubleArray()), 3)
    }

    companion object {
        private const val MS_PER_SECOND = 1_000.0
        private const val NAME_CHARS = 18

        private val BASE = CalibrationConfig.BASELINE
        private val DEFAULT_OPTIONS = GridFitOptions()

        private fun options(
            matchToleranceFraction: Double = DEFAULT_OPTIONS.matchToleranceFraction,
            snapToleranceMs: Double = DEFAULT_OPTIONS.snapToleranceMs,
            driftToleranceMs: Double = DEFAULT_OPTIONS.driftToleranceMs,
            driftFraction: Double = DEFAULT_OPTIONS.driftFraction,
            regionToleranceMs: Double = DEFAULT_OPTIONS.regionToleranceMs,
            minRegionBeats: Int = DEFAULT_OPTIONS.minRegionBeats,
        ) = GridFitOptions(
            matchToleranceFraction = matchToleranceFraction,
            snapToleranceMs = snapToleranceMs,
            minBeats = DEFAULT_OPTIONS.minBeats,
            minCoverage = DEFAULT_OPTIONS.minCoverage,
            driftToleranceMs = driftToleranceMs,
            driftFraction = driftFraction,
            regionToleranceMs = regionToleranceMs,
            regionConfirmBeats = DEFAULT_OPTIONS.regionConfirmBeats,
            minRegionBeats = minRegionBeats,
            regionSnapToleranceMs = DEFAULT_OPTIONS.regionSnapToleranceMs,
        )

        private val PRIORS: List<CalibrationConfig> = buildList {
            for (max in listOf(180.0, 210.0, 230.0)) for (preferred in listOf(110.0, 120.0, 130.0, 150.0)) {
                add(BASE.copy(name = "prior 70-${max.toInt()} @${preferred.toInt()}", prior = TempoPrior(70.0, max, preferred)))
            }
        }

        private val VARIABLE = BASE.copy(name = "tempo segments (D1.12)", variableTempo = true)

        val GRID_VARIANTS: List<CalibrationConfig> = listOf(CalibrationConfig.PRE_D1_20, BASE) + PRIORS + listOf(
            BASE.copy(name = "phase refinement 40 ms", refine = true),
            BASE.copy(name = "phase refinement 20 ms", refine = true, maxShiftMs = 20.0),
            BASE.copy(name = "phase refinement 40 ms, contrast 5", refine = true, minContrast = 5.0),
        ) + listOf(0.0, 5.0, 8.0, 12.0, 15.0).map { BASE.copy(name = "model latency ${it.toInt()} ms", modelLatencyMs = it) } + listOf(
            BASE.copy(name = "match tolerance 0.08", fitOptions = options(matchToleranceFraction = 0.08)),
            BASE.copy(name = "match tolerance 0.18", fitOptions = options(matchToleranceFraction = 0.18)),
            BASE.copy(name = "snap tolerance 4 ms", fitOptions = options(snapToleranceMs = 4.0)),
            BASE.copy(name = "snap tolerance 16 ms", fitOptions = options(snapToleranceMs = 16.0)),
        ) + variableOptions()

        private fun variableOptions(): List<CalibrationConfig> = listOf(
            VARIABLE,
            VARIABLE.copy(name = "segments, drift tolerance 40 ms", fitOptions = options(driftToleranceMs = 40.0)),
            VARIABLE.copy(name = "segments, drift fraction 0.20", fitOptions = options(driftFraction = 0.20)),
            VARIABLE.copy(name = "segments, region tolerance 10 ms", fitOptions = options(regionToleranceMs = 10.0)),
            VARIABLE.copy(name = "segments, min region 8 beats", fitOptions = options(minRegionBeats = 8)),
            VARIABLE.copy(name = "segments, min region 32 beats", fitOptions = options(minRegionBeats = 32)),
        )

        val VARIABLE_VARIANTS: List<CalibrationConfig> = listOf(CalibrationConfig.PRE_D1_20, BASE) + variableOptions()

        val KEY_VARIANTS: List<CalibrationConfig> = buildList {
            for (profile in listOf(KeyProfile.EDMA, KeyProfile.TEMPERLEY, KeyProfile.KRUMHANSL_KESSLER)) {
                for (weighting in FrameWeighting.entries) {
                    add(BASE.copy(name = "${profile.id} ${weighting.name.lowercase()}", keyProfile = profile, weighting = weighting))
                }
            }
        }

        val EXPONENTS: List<Double> = listOf(0.5, 0.75, 1.0, 1.5, 2.0)
    }
}
