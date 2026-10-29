package io.sonora.tools.calibration.runner

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The D1.20 calibration runner: analyses reference tracks of the user's rekordbox export with the real pipeline and
 * scores them against rekordbox's own analysis (beats, downbeats, tempo, key, waveforms). Reports go to `--out`
 * (inside `tools/calibration/build/`, git-ignored); derived features (never audio) are cached in `--cache` so the
 * tuning round does not decode or infer again. The reference export is only read.
 *
 * This machine overheats, so the run is meant to go through a CPU-capped scope, one track at a time, pausing while the
 * CPU package is above `--max-temp`:
 * ```
 * safe-gradle.sh :tools:calibration:calibrationClasspath
 * systemd-run --user --scope -p CPUQuota=150% -p MemoryMax=6G nice -n 19 \
 *   java -Xmx2g -cp "$(cat tools/calibration/build/calibration-classpath.txt)" \
 *   io.sonora.tools.calibration.runner.CalibrationMain --reference <export root> \
 *   --model tools/beatthis/build/small0.onnx --out tools/calibration/build/reports/calibration \
 *   --cache tools/calibration/build/calibration-cache --limit 5 --threads 2 --label harness
 * ```
 * Options: `--limit N` (subset size), `--variable` (include every variable-tempo track), `--tracks <file>` (explicit
 * selection paths), `--threads N` (ORT intra-op threads), `--max-temp C`, `--label NAME`, `--reanalyse` (ignore
 * the cache), `--evaluate-only` (no decoding and no model: use the cache), `--tune` (the tuning sweep after the baseline),
 * `--bar-sweep` (D1.29: the downbeat variants of the bar phase vote, re-scored from the cache).
 */
object CalibrationMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = try {
            CalibrationOptions.parse(args.toList())
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            exitProcess(2)
        }
        val outcome = CalibrationRun(options) { println(it) }.execute()
        println("Reports: ${outcome.files.joinToString(", ")}")
        exitProcess(if (outcome.results.isEmpty()) 1 else 0)
    }
}

/** See [CalibrationMain]. */
data class CalibrationOptions(
    val reference: Path,
    val model: Path?,
    val out: Path,
    val cache: Path,
    val limit: Int = 5,
    val includeVariable: Boolean = false,
    val tracksFile: Path? = null,
    val threads: Int = 2,
    val maxTempC: Double = 85.0,
    val label: String = "run",
    val reanalyse: Boolean = false,
    val evaluateOnly: Boolean = false,
    val tune: Boolean = false,
    /** Re-scores the cached captures under the bar phase variants of [BarPhaseSweep] (no model, no decoding). */
    val barSweep: Boolean = false,
    /** Frames per model call and discarded border; the defaults are the app's descriptor (D1.16/D1.21). */
    val chunkFrames: Int = HostBeatThisModel.DESCRIPTOR.chunkFrames,
    val borderFrames: Int = HostBeatThisModel.DESCRIPTOR.borderFrames,
    /** The analysis parameters the report scores: [CalibrationConfig.BASELINE] or `--config pre-d1.20`. */
    val config: CalibrationConfig = CalibrationConfig.BASELINE,
) {
    /** The captures of one model chunking live apart: they hold different beat candidates. */
    val modelCache: Path get() = cache.resolve("chunk$chunkFrames-border$borderFrames")

    companion object {
        fun parse(args: List<String>): CalibrationOptions {
            val values = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < args.size) {
                val name = args[i]
                require(name.startsWith("--")) { "Unexpected argument: $name" }
                if (name in FLAGS) {
                    flags += name
                    i++
                } else {
                    require(i + 1 < args.size) { "$name needs a value" }
                    values[name] = args[i + 1]
                    i += 2
                }
            }
            fun path(name: String) = Paths.get(values[name] ?: throw IllegalArgumentException("$name is required"))
            return CalibrationOptions(
                reference = path("--reference"),
                // The model is only opened to analyse tracks missing from the cache; an evaluate-only run never needs it.
                model = if ("--evaluate-only" in flags) values["--model"]?.let { Paths.get(it) } else path("--model"),
                out = path("--out"),
                cache = path("--cache"),
                limit = values["--limit"]?.toInt() ?: 5,
                includeVariable = "--variable" in flags,
                tracksFile = values["--tracks"]?.let { Paths.get(it) },
                threads = values["--threads"]?.toInt() ?: 2,
                maxTempC = values["--max-temp"]?.toDouble() ?: 85.0,
                label = values["--label"] ?: "run",
                reanalyse = "--reanalyse" in flags,
                evaluateOnly = "--evaluate-only" in flags,
                tune = "--tune" in flags,
                barSweep = "--bar-sweep" in flags,
                chunkFrames = values["--chunk-frames"]?.toInt() ?: HostBeatThisModel.DESCRIPTOR.chunkFrames,
                borderFrames = values["--border-frames"]?.toInt() ?: HostBeatThisModel.DESCRIPTOR.borderFrames,
                config = when (val name = values["--config"] ?: "baseline") {
                    "baseline" -> CalibrationConfig.BASELINE
                    "pre-d1.20" -> CalibrationConfig.PRE_D1_20
                    "pre-d1.29" -> CalibrationConfig.PRE_D1_29
                    else -> throw IllegalArgumentException("--config is baseline, pre-d1.20 or pre-d1.29, not $name")
                },
            )
        }

        private val FLAGS = setOf("--variable", "--reanalyse", "--evaluate-only", "--tune", "--bar-sweep")
    }
}

/** What a run produced: the baseline results and the report files written. */
class CalibrationOutcome(val results: List<TrackResult>, val files: List<Path>)

/** One calibration run; see [CalibrationMain]. */
class CalibrationRun(private val options: CalibrationOptions, private val log: (String) -> Unit) {

    fun execute(): CalibrationOutcome {
        Files.createDirectories(options.out)
        Files.createDirectories(options.modelCache)
        val library = ReferenceLibrary(options.reference)
        val match = ReferencePaths.match(library.selection(), library.tracks)
        if (match.unmatched.isNotEmpty()) log("${match.unmatched.size} selection entries have no export.pdb row: ${match.unmatched.take(5)}")
        val byPath = match.matched.toMap()

        val chosen = chooseTracks(library, match)
        val selectionFile = options.out.resolve("${options.label}-selection.txt")
        Files.write(selectionFile, chosen)
        log("${chosen.size} tracks selected (${selectionFile.fileName})")

        val captures = LinkedHashMap<String, TrackCapture>()
        val failures = ArrayList<String>()
        val model = if (options.evaluateOnly) null else HostBeatThisModel.open(requireNotNull(options.model), options.threads, options.chunkFrames, options.borderFrames)
        try {
            val analyser = model?.let { TrackAnalyser(it, FfmpegDecoder(log = options.cache.resolve("ffmpeg.log").toFile())) }
            for ((index, path) in chosen.withIndex()) {
                val track = byPath.getValue(path)
                val cacheFile = options.modelCache.resolve(cacheName(path))
                val cached = if (!options.reanalyse && Files.isRegularFile(cacheFile)) runCatching { Files.newInputStream(cacheFile).use(CaptureCodec::read) }.getOrNull() else null
                if (cached != null) {
                    captures[path] = cached
                    continue
                }
                if (options.evaluateOnly) {
                    log("[${index + 1}/${chosen.size}] not in the cache, skipped: $path")
                    continue
                }
                val temperature = Thermal.waitBelow(options.maxTempC, log = log) ?: Double.NaN
                try {
                    val capture = checkNotNull(analyser).analyse(library.audioFile(track), path, temperature)
                    Files.newOutputStream(cacheFile).use { CaptureCodec.write(capture, it) }
                    captures[path] = capture
                    val after = Thermal.readPackageCelsius() ?: Double.NaN
                    log(
                        "[${index + 1}/${chosen.size}] ${capture.format} ${String.format(Locale.ROOT, "%.1f", capture.durationMs / 1000)} s audio, " +
                            "${String.format(Locale.ROOT, "%.1f", capture.wallMs / 1000.0)} s analysis, CPU ${temperature.toInt()} -> ${after.toInt()} C, " +
                            "stages ${capture.stageMs.entries.joinToString { "${it.key}=${it.value}" }}: $path",
                    )
                } catch (e: Exception) {
                    failures += "$path: $e"
                    log("[${index + 1}/${chosen.size}] FAILED $path: $e")
                }
            }
        
        } finally {
            model?.close()
        }

        val pairs = captures.map { (path, capture) -> capture to library.load(path, byPath.getValue(path)) }
        val config = options.config
        val results = pairs.map { (capture, reference) ->
            Evaluator.evaluate(capture, reference, config, capture.wallMs / MS_PER_SECOND, capture.temperatureC)
        }
        val reproduced = pairs.count { (capture, _) ->
            Evaluator.grid(capture, config) == capture.pipelineGrid && Evaluator.key(capture, config) == capture.pipelineKey
        }
        val notes = listOf(
            "Reference: rekordbox export `${options.reference.fileName}`, ${match.matched.size} selection entries with an export row; " +
                "${captures.size} of ${chosen.size} selected tracks analysed or cached, ${failures.size} failed.",
            "Analysis: DjAnalysisPipeline sinks (BandWaveformSink, HpcpSink, LogMelSink, OnsetEnvelopeSink), Beat This! small " +
                "on the host ONNX Runtime CPU (${options.threads} intra-op threads, chunks of ${options.chunkFrames} frames with a " +
                "${options.borderFrames}-frame border), ffmpeg -threads 1 decode at the file's rate. No qm-dsp on the host: " +
                "FallbackPolicy.ONLY_WITHOUT_MODEL with an empty fallback.",
            "Scored under `${config.name}`: ${describe(config)}.",
            "The grid and key re-derived from the cached features equal the ones the pipeline stored at capture time on " +
                "$reproduced of ${pairs.size} tracks (captures taken under other defaults differ by design).",
        ) + failures.map { "Failed: $it" }

        val files = ArrayList<Path>()
        // `files += path` would be ambiguous: a Path is itself an Iterable<Path>.
        files.add(write("${options.label}.csv", CalibrationReport.csv(results)))
        files.add(write("${options.label}.md", CalibrationReport.markdown("D1.20 calibration: ${options.label}", results, notes)))
        if (options.barSweep && pairs.isNotEmpty()) files.add(write("${options.label}-barphase.md", barPhaseReport(pairs)))
        if (options.tune && pairs.isNotEmpty()) files.addAll(Tuning(pairs, log).writeReport(options.out, options.label))
        return CalibrationOutcome(results, files)
    }

    /** The D1.29 study: every [BarPhaseSweep] variant over [pairs], and the confidence of the default on the accurate-beat tracks. */
    private fun barPhaseReport(pairs: List<Pair<TrackCapture, ReferenceTrack>>): String {
        fun results(config: CalibrationConfig) = pairs.map { (capture, reference) ->
            Evaluator.evaluate(capture, reference, config, 0.0, Double.NaN, includeWaveform = false)
        }
        val baseline = results(CalibrationConfig.PRE_D1_29)
        val scores = BarPhaseSweep.variants.map { config ->
            log("bar phase ${config.name}")
            BarPhaseSweep.score(config.name, if (config === CalibrationConfig.PRE_D1_29) baseline else results(config), baseline)
        }
        val confidence = pairs.zip(results(CalibrationConfig.BASELINE)).mapNotNull { (pair, result) ->
            if (result.beat.fMeasure <= ACCURATE_BEATS_F) return@mapNotNull null
            val (capture, _) = pair
            Evaluator.gridWithConfidence(capture, CalibrationConfig.BASELINE)?.let { (_, c) -> c to (result.barPhase.bestShift == 0) }
        }
        return "# D1.29 bar phase: ${options.label}\n\n${pairs.size} tracks, cached features re-scored.\n\n" + BarPhaseSweep.markdown(scores, confidence)
    }

    private fun describe(c: CalibrationConfig): String = listOf(
        if (c.variableTempo) "tempo segments (GridFitter.fit)" else "one constant tempo (GridFitter.fitConstant)",
        "model latency ${c.modelLatencyMs} ms taken off",
        if (c.refine) "onset phase refinement (window ${c.maxShiftMs} ms, contrast ${c.minContrast})" else "no phase refinement",
        c.barPhase?.let { "bar phase from the downbeat probabilities with the start of the track as a prior (weight ${it.introWeight}, window ${it.introWindowMs} ms)" }
            ?: "bar phase: the grid fit's own vote",
        "prior ${c.prior.minBpm.toInt()}-${c.prior.maxBpm.toInt()} BPM centred on ${c.prior.preferredBpm.toInt()}",
        "key ${c.keyProfile.id} ${c.weighting.name.lowercase()}",
    ).joinToString(", ")

    private fun chooseTracks(library: ReferenceLibrary, match: SelectionMatch): List<String> {
        val matchedPaths = match.matched.map { it.first }
        options.tracksFile?.let { file ->
            val wanted = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
            val byKey = matchedPaths.associateBy { ReferencePaths.key(it) }
            return wanted.mapNotNull { byKey[ReferencePaths.key(it)] ?: null.also { _ -> log("Not in the selection: $it") } }
        }
        val candidates = match.matched.map { (path, track) ->
            val grid = library.grid(track)
            SelectionCandidate(path, ReferencePaths.format(path), grid.dominantBpm.takeUnless { it.isNaN() } ?: track.tempoBpm, grid.isVariable)
        }
        return TrackSelection.spread(candidates, options.limit, options.includeVariable).map { it.path }
    }

    private fun write(name: String, text: String): Path {
        val file = options.out.resolve(name)
        Files.writeString(file, text)
        return file
    }

    companion object {
        private const val MS_PER_SECOND = 1_000.0
        private const val ACCURATE_BEATS_F = 0.9

        /** The cache file of a selection path: a hash, so no track name ends up in a file name. */
        fun cacheName(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(ReferencePaths.key(path).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32) + ".scap"
        }
    }
}
