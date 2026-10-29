package io.sonora.tools.calibration.runner

import io.sonora.tools.calibration.BandOrderResult
import io.sonora.tools.calibration.BeatMetrics
import io.sonora.tools.calibration.MarkdownTable
import io.sonora.tools.calibration.MarkdownTable.number
import io.sonora.tools.calibration.Stats
import io.sonora.tools.calibration.TempoMetrics
import kotlin.math.abs

/** The headline numbers of a set of [TrackResult]s; means are `NaN` when nothing contributes. */
class Aggregates(
    val tracks: Int,
    val withGrid: Int,
    /** Share of all tracks (a missing grid is a miss) with the right tempo, MIREX accuracy 1 (4 %). */
    val acc1: Double,
    /** The same, accepting x2, /2, x3/2, x2/3. */
    val acc2: Double,
    val meanF: Double,
    val medianF: Double,
    val meanFTrimmed: Double,
    val meanPrecision: Double,
    val meanRecall: Double,
    val downbeatAccuracy: Double,
    val barAgreement: Double,
    val keyTracks: Int,
    val keyMirex: Double,
    val keyExact: Double,
    /** Median and 95th percentile of every matched beat's signed offset, all tracks pooled. */
    val offsetMedianMs: Double,
    val offsetP95AbsMs: Double,
    val variableTracks: Int,
    val variableMeanF: Double,
    val meanAnalysisSeconds: Double,
    /** The model's raw detections before the grid fit: mean F and downbeat accuracy over the tracks that have them. */
    val meanModelF: Double = Double.NaN,
    val modelDownbeatAccuracy: Double = Double.NaN,
    /** Tracks whose beats are accurate (F above 0.9): the ones where a wrong bar phase is the bar phase's own fault. */
    val beatAccurateTracks: Int = 0,
    /** Of those, the tracks whose bar phase agrees with the reference's (no shift needed). */
    val beatAccurateRightPhase: Int = 0,
)

/** How often one stored band order won, over how many tracks, and its mean correlation over all of them. */
class BandOrderVote(val fileOrder: String, val wins: Int, val tracks: Int, val meanCorrelation: Double)

/** Renders the calibration results: one CSV row per track, a Markdown report and a before/after comparison. */
object CalibrationReport {

    private val BANDS = listOf("low", "mid", "high")

    /** One row per result; numbers with a dot whatever the locale, `NaN` and missing values empty. */
    fun csv(results: List<TrackResult>): String {
        val correlationKeys = results.flatMap { it.waveformCorrelations.keys }.toSortedSet()
        val header = listOf(
            "path", "format", "config", "ref_bpm", "ref_variable", "our_bpm", "octave", "segments", "f", "precision", "recall",
            "f_trim", "offset_median_ms", "offset_p95_ms", "offset_mean_ms", "downbeat_acc", "bar_agreement", "bar_shift",
            "bar_shifted_agreement", "ref_key", "our_key", "key_score", "pwv7_order", "pwv7_order_mean", "pwv7_low", "pwv7_mid",
            "pwv7_high", "pwv6_order", "pwv6_order_mean", "lag_ms", "analysis_s", "audio_s", "temp_c", "model_f", "model_offset_median_ms",
            "model_downbeat_acc",
        ) + correlationKeys
        return buildString {
            appendLine(header.joinToString(","))
            for (r in results) {
                val best = r.bandOrder?.best
                val row = listOf(
                    r.path, r.format, r.config, num(r.referenceBpm, 2), r.referenceVariable.toString(), num(r.ourBpm, 2),
                    r.octave?.name ?: "", r.segments.toString(), num(r.beat.fMeasure, 4), num(r.beat.precision, 4),
                    num(r.beat.recall, 4), num(r.beatTrimmed.fMeasure, 4), num(r.beat.offsets.median, 2),
                    num(r.beat.offsets.p95, 2), num(r.beat.offsets.mean, 2), num(r.downbeat.accuracy, 4),
                    num(r.barPhase.agreement, 4), r.barPhase.bestShift.toString(), num(r.barPhase.shiftedAgreement, 4),
                    r.referenceKey?.alphanumeric ?: "", r.ourKey?.alphanumeric ?: "", num(r.keyScore, 2),
                    best?.let { fileOrder(it.assignment) } ?: "", num(best?.meanCorrelation ?: Double.NaN, 4),
                    num(best?.correlations?.get(0) ?: Double.NaN, 4), num(best?.correlations?.get(1) ?: Double.NaN, 4),
                    num(best?.correlations?.get(2) ?: Double.NaN, 4),
                    r.previewBandOrder?.best?.let { fileOrder(it.assignment) } ?: "",
                    num(r.previewBandOrder?.best?.meanCorrelation ?: Double.NaN, 4), num(r.waveformLagMs, 2),
                    num(r.analysisSeconds, 2), num(r.audioSeconds, 2), num(r.temperatureC, 1),
                    num(r.modelBeat?.fMeasure ?: Double.NaN, 4), num(r.modelBeat?.offsets?.median ?: Double.NaN, 2),
                    num(r.modelDownbeat?.accuracy ?: Double.NaN, 4),
                ) + correlationKeys.map { num(r.waveformCorrelations[it] ?: Double.NaN, 4) }
                appendLine(row.joinToString(",") { csvCell(it) })
            }
        }
    }

    fun aggregates(results: List<TrackResult>): Aggregates {
        val n = results.size
        val withGrid = results.filter { it.segments > 0 }
        val keyed = results.filter { it.referenceKey != null }
        val offsets = results.flatMap { it.beat.offsetsMs.toList() }.toDoubleArray()
        val variable = results.filter { it.referenceVariable }
        val accurate = results.filter { it.beat.fMeasure > ACCURATE_BEATS_F }
        fun share(count: Int) = if (n == 0) Double.NaN else count.toDouble() / n
        fun mean(values: List<Double>) = Stats.mean(values.filter { !it.isNaN() }.toDoubleArray())
        return Aggregates(
            tracks = n,
            withGrid = withGrid.size,
            acc1 = share(results.count { it.octave?.isAcc1 == true }),
            acc2 = share(results.count { it.octave?.isAcc2 == true }),
            meanF = mean(results.map { it.beat.fMeasure }),
            medianF = Stats.median(results.map { it.beat.fMeasure }.toDoubleArray()),
            meanFTrimmed = mean(results.map { it.beatTrimmed.fMeasure }),
            meanPrecision = mean(results.map { it.beat.precision }),
            meanRecall = mean(results.map { it.beat.recall }),
            downbeatAccuracy = mean(results.map { it.downbeat.accuracy }),
            barAgreement = mean(withGrid.map { it.barPhase.agreement }),
            keyTracks = keyed.size,
            keyMirex = mean(keyed.map { it.keyScore }),
            keyExact = if (keyed.isEmpty()) Double.NaN else keyed.count { it.ourKey == it.referenceKey }.toDouble() / keyed.size,
            offsetMedianMs = Stats.median(offsets),
            offsetP95AbsMs = Stats.percentile(DoubleArray(offsets.size) { abs(offsets[it]) }, P95).takeIf { offsets.isNotEmpty() } ?: Double.NaN,
            variableTracks = variable.size,
            variableMeanF = mean(variable.map { it.beat.fMeasure }),
            meanAnalysisSeconds = mean(results.map { it.analysisSeconds }),
            meanModelF = mean(results.mapNotNull { it.modelBeat?.fMeasure }),
            modelDownbeatAccuracy = mean(results.mapNotNull { it.modelDownbeat?.accuracy }),
            beatAccurateTracks = accurate.size,
            beatAccurateRightPhase = accurate.count { it.barPhase.bestShift == 0 },
        )
    }

    /** The bands a stored file order holds, given an assignment (our band `i` is stored plane `assignment[i]`). */
    fun fileOrder(assignment: List<Int>): String {
        require(assignment.sorted() == listOf(0, 1, 2)) { "Not a permutation of three planes: $assignment" }
        return (0 until 3).joinToString(", ") { plane -> BANDS[assignment.indexOf(plane)] }
    }

    /**
     * For each of the six possible stored orders: how many tracks it won, and its mean correlation over every track
     * that has the tag, best first.
     */
    fun bandOrderVotes(results: List<TrackResult>, pick: (TrackResult) -> BandOrderResult?): List<BandOrderVote> {
        val orders = results.mapNotNull(pick)
        if (orders.isEmpty()) return emptyList()
        val byAssignment = orders.flatMap { it.ranked }.groupBy { it.assignment }
        return byAssignment.map { (assignment, candidates) ->
            BandOrderVote(
                fileOrder = fileOrder(assignment),
                wins = orders.count { it.best.assignment == assignment },
                tracks = orders.size,
                meanCorrelation = candidates.map { it.meanCorrelation }.average(),
            )
        }.sortedByDescending { it.meanCorrelation }
    }

    /**
     * The mean correlation of our band `i` (low, mid, high) with stored byte `j` of each column, over the tracks with
     * the tag: every pairing of a [BandOrderResult] holds three cells of the same 3 x 3 matrix, so the six pairings
     * give it back whole.
     */
    fun bandMatrix(results: List<TrackResult>, pick: (TrackResult) -> BandOrderResult?): Array<DoubleArray> {
        val sum = Array(3) { DoubleArray(3) }
        var tracks = 0
        for (order in results.mapNotNull(pick)) {
            tracks++
            val cells = Array(3) { DoubleArray(3) }
            for (candidate in order.ranked) {
                candidate.assignment.forEachIndexed { band, stored -> cells[band][stored] = candidate.correlations[band] }
            }
            for (i in 0 until 3) for (j in 0 until 3) sum[i][j] += cells[i][j]
        }
        return Array(3) { i -> DoubleArray(3) { j -> if (tracks == 0) Double.NaN else sum[i][j] / tracks } }
    }

    fun markdown(title: String, results: List<TrackResult>, notes: List<String>): String = buildString {
        appendLine("# $title")
        appendLine()
        for (note in notes) appendLine("- $note")
        if (notes.isNotEmpty()) appendLine()

        val a = aggregates(results)
        appendLine("## Summary")
        appendLine()
        append(
            MarkdownTable.render(
                listOf("metric", "value"),
                listOf(
                    listOf("tracks (with a grid)", "${a.tracks} (${a.withGrid})"),
                    listOf("tempo accuracy 1 / 2 (4 %)", "${number(a.acc1, 3)} / ${number(a.acc2, 3)}"),
                    listOf("beat F-measure +-70 ms, mean (median)", "${number(a.meanF, 3)} (${number(a.medianF, 3)})"),
                    listOf("beat F-measure, first 5 s trimmed", number(a.meanFTrimmed, 3)),
                    listOf("precision / recall", "${number(a.meanPrecision, 3)} / ${number(a.meanRecall, 3)}"),
                    listOf("model beats before the grid: F / downbeat accuracy", "${number(a.meanModelF, 3)} / ${number(a.modelDownbeatAccuracy, 3)}"),
                    listOf("downbeat accuracy", number(a.downbeatAccuracy, 3)),
                    listOf("bar position agreement (tracks with a grid)", number(a.barAgreement, 3)),
                    listOf("bar phase right on tracks with F above 0.9", "${a.beatAccurateRightPhase} of ${a.beatAccurateTracks}"),
                    listOf("key MIREX score (exact) over ${a.keyTracks} keyed tracks", "${number(a.keyMirex, 3)} (${number(a.keyExact, 3)})"),
                    listOf("beat offset median / p95 of abs, ms", "${number(a.offsetMedianMs, 1)} / ${number(a.offsetP95AbsMs, 1)}"),
                    listOf("variable-tempo tracks, mean F", "${a.variableTracks}, ${number(a.variableMeanF, 3)}"),
                    listOf("analysis time per track, s", number(a.meanAnalysisSeconds, 1)),
                ),
            ),
        )
        appendLine()

        appendLine("## Tempo octave classes")
        appendLine()
        val histogram = TempoMetrics.histogram(results.mapNotNull { it.octave })
        val octaveRows = histogram.map { (k, v) -> listOf(k.name, v.toString()) } + listOf(listOf("no grid", results.count { it.octave == null }.toString()))
        append(MarkdownTable.render(listOf("class", "tracks"), octaveRows, rightAligned = setOf(1)))
        appendLine()

        appendLine("## Beat offsets per format")
        appendLine()
        appendLine("Signed `ours - rekordbox` of every matched beat (+-70 ms), pooled per format, in ms. Positive: our beats are late.")
        appendLine()
        val perFormat = BeatMetrics.offsetSummaryByFormat(results.map { it.format to it.beat })
        append(
            MarkdownTable.render(
                MarkdownTable.SUMMARY_HEADERS,
                perFormat.map { (format, summary) -> MarkdownTable.summaryRow(format, summary, 1) },
                rightAligned = (1..6).toSet(),
            ),
        )
        appendLine()
        appendLine("Per-track median offset by format (one value per track, ms):")
        appendLine()
        val trackMedians = results.groupBy { it.format }.toSortedMap().map { (format, rs) ->
            MarkdownTable.summaryRow(format, Stats.summarize(rs.map { it.beat.offsets.median }.filter { !it.isNaN() }.toDoubleArray()), 1)
        }
        append(MarkdownTable.render(MarkdownTable.SUMMARY_HEADERS, trackMedians, rightAligned = (1..6).toSet()))
        appendLine()
        appendLine("Waveform time base (cross-correlation of our peak plane with the PWV5/PWV3 detail height), ms. Positive: our waveform is late:")
        appendLine()
        val lags = results.groupBy { it.format }.toSortedMap().map { (format, rs) ->
            MarkdownTable.summaryRow(format, Stats.summarize(rs.map { it.waveformLagMs }.filter { !it.isNaN() }.toDoubleArray()), 1)
        }
        append(MarkdownTable.render(MarkdownTable.SUMMARY_HEADERS, lags, rightAligned = (1..6).toSet()))
        appendLine()

        appendLine("## Band order")
        appendLine()
        for ((label, pick) in listOf<Pair<String, (TrackResult) -> BandOrderResult?>>("PWV7 (detail)" to { it.bandOrder }, "PWV6 (preview)" to { it.previewBandOrder })) {
            val votes = bandOrderVotes(results, pick)
            if (votes.isEmpty()) continue
            appendLine("$label: what the three bytes of a column hold, by mean Pearson correlation with our low/mid/high planes.")
            appendLine()
            append(
                MarkdownTable.render(
                    listOf("stored order", "tracks won", "tracks", "mean correlation"),
                    votes.map { listOf(it.fileOrder, it.wins.toString(), it.tracks.toString(), number(it.meanCorrelation, 3)) },
                    rightAligned = setOf(1, 2, 3),
                ),
            )
            appendLine()
            val matrix = bandMatrix(results, pick)
            append(
                MarkdownTable.render(
                    listOf("our band", "byte 0", "byte 1", "byte 2"),
                    BANDS.indices.map { i -> listOf(BANDS[i]) + (0 until 3).map { j -> number(matrix[i][j], 3) } },
                    rightAligned = setOf(1, 2, 3),
                ),
            )
            appendLine()
        }
        val perBand = results.mapNotNull { r -> r.bandOrder?.let { order -> order.ranked.first { it.assignment == bestGlobal(results) } } }
        if (perBand.isNotEmpty()) {
            appendLine("Per-band correlation under the winning order (${fileOrder(bestGlobal(results)!!)}):")
            appendLine()
            append(
                MarkdownTable.render(
                    MarkdownTable.SUMMARY_HEADERS,
                    BANDS.indices.map { b -> MarkdownTable.summaryRow(BANDS[b], Stats.summarize(perBand.map { it.correlations[b] }.toDoubleArray()), 3) },
                    rightAligned = (1..6).toSet(),
                ),
            )
            appendLine()
        }

        val correlationKeys = results.flatMap { it.waveformCorrelations.keys }.toSortedSet()
        if (correlationKeys.isNotEmpty()) {
            appendLine("## Other waveform correlations")
            appendLine()
            append(
                MarkdownTable.render(
                    MarkdownTable.SUMMARY_HEADERS,
                    correlationKeys.map { key ->
                        MarkdownTable.summaryRow(key, Stats.summarize(results.mapNotNull { it.waveformCorrelations[key] }.toDoubleArray()), 3)
                    },
                    rightAligned = (1..6).toSet(),
                ),
            )
            appendLine()
        }

        appendLine("## Keys")
        appendLine()
        val keyed = results.filter { it.referenceKey != null }
        val relations = listOf(1.0 to "same key", 0.5 to "fifth above", 0.3 to "relative", 0.2 to "parallel", 0.0 to "other or none")
        append(
            MarkdownTable.render(
                listOf("relation (MIREX score)", "tracks"),
                relations.map { (score, label) -> listOf("$label (${number(score, 1)})", keyed.count { it.keyScore == score }.toString()) },
                rightAligned = setOf(1),
            ),
        )
        appendLine()

        appendLine("## Timing")
        appendLine()
        append(
            MarkdownTable.render(
                MarkdownTable.SUMMARY_HEADERS,
                listOf(
                    MarkdownTable.summaryRow("analysis s / track", Stats.summarize(results.map { it.analysisSeconds }.toDoubleArray()), 1),
                    MarkdownTable.summaryRow("audio s / track", Stats.summarize(results.map { it.audioSeconds }.toDoubleArray()), 1),
                    MarkdownTable.summaryRow(
                        "analysis / audio",
                        Stats.summarize(results.filter { it.audioSeconds > 0 }.map { it.analysisSeconds / it.audioSeconds }.toDoubleArray()),
                        3,
                    ),
                    MarkdownTable.summaryRow("CPU package C at start", Stats.summarize(results.map { it.temperatureC }.filter { !it.isNaN() }.toDoubleArray()), 1),
                ),
                rightAligned = (1..6).toSet(),
            ),
        )
        appendLine()

        appendLine("## Tracks")
        appendLine()
        append(
            MarkdownTable.render(
                listOf("track", "fmt", "ref BPM", "our BPM", "octave", "seg", "F", "F 5s", "offset ms", "downbeat", "bar shift", "model F", "model db", "ref key", "our key", "key", "PWV7", "lag ms", "s"),
                results.map { r ->
                    listOf(
                        r.path.substringAfterLast('/').take(TRACK_NAME_CHARS), r.format, number(r.referenceBpm, 2) + if (r.referenceVariable) "*" else "",
                        number(r.ourBpm, 2), r.octave?.name ?: "none", r.segments.toString(), number(r.beat.fMeasure, 3),
                        number(r.beatTrimmed.fMeasure, 3), number(r.beat.offsets.median, 1), number(r.downbeat.accuracy, 2),
                        r.barPhase.bestShift.toString(), number(r.modelBeat?.fMeasure ?: Double.NaN, 3),
                        number(r.modelDownbeat?.accuracy ?: Double.NaN, 2), r.referenceKey?.alphanumeric ?: "-", r.ourKey?.alphanumeric ?: "-",
                        number(r.keyScore, 1), r.bandOrder?.best?.let { fileOrder(it.assignment) } ?: "-", number(r.waveformLagMs, 1),
                        number(r.analysisSeconds, 1),
                    )
                },
                rightAligned = setOf(2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 15, 17, 18),
            ),
        )
        appendLine()
        appendLine("`*` variable tempo in the reference (BPM shown is the tempo held by the most beats).")
    }

    /** A table with one row per configuration and its [Aggregates]. */
    fun comparison(rows: List<Pair<String, Aggregates>>): String = MarkdownTable.render(
        listOf(
            "config", "acc1", "acc2", "F", "F median", "F 5s", "downbeat", "bar agree", "key MIREX", "key exact", "offset median",
            "|offset| p95", "variable F", "phase right (F>0.9)",
        ),
        rows.map { (name, a) ->
            listOf(
                name, number(a.acc1, 3), number(a.acc2, 3), number(a.meanF, 3), number(a.medianF, 3), number(a.meanFTrimmed, 3),
                number(a.downbeatAccuracy, 3), number(a.barAgreement, 3), number(a.keyMirex, 3), number(a.keyExact, 3),
                number(a.offsetMedianMs, 1), number(a.offsetP95AbsMs, 1), number(a.variableMeanF, 3),
                "${a.beatAccurateRightPhase} of ${a.beatAccurateTracks}",
            )
        },
        rightAligned = (1..13).toSet(),
    )

    /** The assignment with the highest mean correlation over all tracks with `PWV7`, or `null` without any. */
    private fun bestGlobal(results: List<TrackResult>): List<Int>? {
        val orders = results.mapNotNull { it.bandOrder }
        if (orders.isEmpty()) return null
        return orders.flatMap { it.ranked }.groupBy { it.assignment }.maxBy { (_, c) -> c.map { it.meanCorrelation }.average() }.key
    }

    private fun num(value: Double, decimals: Int): String = if (value.isNaN()) "" else number(value, decimals)

    private fun csvCell(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private const val P95 = 95.0

    /** Beats this accurate (F-measure) leave a wrong bar phase as the bar phase's own error. */
    private const val ACCURATE_BEATS_F = 0.9
    private const val TRACK_NAME_CHARS = 40
}
