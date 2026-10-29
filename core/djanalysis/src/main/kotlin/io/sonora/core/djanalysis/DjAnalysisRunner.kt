package io.sonora.core.djanalysis

import io.sonora.core.djengine.pipeline.beatSourceId
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.DjAnalysisStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * The three callbacks of [DjAnalysisQueue][io.sonora.core.djengine.schedule.DjAnalysisQueue]: [identify], [isAnalysed]
 * and [analyze], over the identifier, the permanent [store] and the analysis [passes], with one telemetry line per
 * analysed track ([DjAnalysisReport]).
 *
 * - [isAnalysed] is `true` only for an analysis that is stored complete: one restored from a backup without its waveform
 *   counts as not analysed, so the queue hands it to [analyze], which then computes only the waveform.
 * - [analyze] runs the whole analysis and stores it with [DjAnalysisStore.putIfAbsent] (the first result wins: an
 *   analysis, its grid edits and cues are never replaced), or, for a restored analysis, only the waveform pass and
 *   [DjAnalysisStore.fillMissingWaveform] (grid, key and edits untouched).
 * - [reanalyze] runs the whole analysis and replaces the stored one ([DjAnalysisStore.replace]), on the user's request.
 * - Failures are logged and thrown (the queue marks the track failed); cancellation is thrown and not logged.
 *
 * @param identify the audio identity of a uri (the app's [SafAudioIdentifier]); timed as [AnalysisStage.IDENTIFY].
 * @param monotonicNanos the clock of the IDENTIFY and STORE timings.
 */
class DjAnalysisRunner(
    private val identifier: suspend (String) -> AudioId,
    private val store: DjAnalysisStore,
    private val passes: AnalysisPasses,
    private val log: DjAnalysisLog,
    private val debuggable: Boolean,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    /** How long the last identification of each uri took, until its analysis reports it. */
    private val identifyMs = ConcurrentHashMap<String, Long>()

    suspend fun identify(sourceUri: String): AudioId {
        val start = monotonicNanos()
        val id = identifier(sourceUri)
        identifyMs[sourceUri] = toMs(monotonicNanos() - start)
        return id
    }

    suspend fun isAnalysed(id: AudioId): Boolean {
        val ids = listOf(id)
        return id in store.analysedAmong(ids) && id !in store.waveformMissingAmong(ids)
    }

    suspend fun analyze(sourceUri: String, id: AudioId, onProgress: (AnalysisStage, Float) -> Unit) {
        try {
            val report = if (id in store.waveformMissingAmong(listOf(id))) {
                val pass = passes.waveform(sourceUri, onProgress)
                val (stored, storeMs) = timed { store.fillMissingWaveform(id, pass.value) }
                report(sourceUri, id, AnalysisKind.WAVEFORM_ONLY, pass, stored, storeMs)
            } else {
                val pass = passes.full(sourceUri, id, onProgress)
                val (stored, storeMs) = timed { store.putIfAbsent(id, pass.value) }
                report(sourceUri, id, AnalysisKind.FULL, pass, stored, storeMs)
            }
            log.info(report.line())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed id=${id.scheme}:${id.hash.take(ID_DIGITS)} ${e::class.java.simpleName}: ${e.message}", e)
            throw e
        } finally {
            identifyMs.remove(sourceUri)
        }
    }

    /**
     * Analyses [sourceUri] again and replaces the stored analysis of [id] with the result (the user asked for it). Only the
     * analysis row is written: the grid edit and the cues live in their own tables under the same audio id and are never
     * touched. A failed or cancelled run stores nothing, so the previous analysis stays.
     */
    suspend fun reanalyze(sourceUri: String, id: AudioId, onProgress: (AnalysisStage, Float) -> Unit) {
        try {
            val pass = passes.full(sourceUri, id, onProgress)
            val (_, storeMs) = timed { store.replace(id, pass.value) }
            log.info(report(sourceUri, id, AnalysisKind.REANALYSIS, pass, stored = true, storeMs = storeMs).line())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed id=${id.scheme}:${id.hash.take(ID_DIGITS)} ${e::class.java.simpleName}: ${e.message}", e)
            throw e
        } finally {
            identifyMs.remove(sourceUri)
        }
    }

    private fun report(
        sourceUri: String,
        id: AudioId,
        kind: AnalysisKind,
        pass: PassResult<*>,
        stored: Boolean,
        storeMs: Long,
    ): DjAnalysisReport {
        val stages = LinkedHashMap<AnalysisStage, Long>()
        identifyMs[sourceUri]?.let { stages[AnalysisStage.IDENTIFY] = it }
        stages.putAll(pass.stageMs)
        stages[AnalysisStage.STORE] = storeMs
        val provenance = pass.provenance
        return DjAnalysisReport(
            audioId = id,
            kind = kind,
            audioMs = pass.audioMs,
            stageMs = stages,
            beatSourceId = provenance?.beatSourceId,
            accelerator = provenance?.accelerator,
            stored = stored,
            debuggable = debuggable,
        )
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val start = monotonicNanos()
        val value = block()
        return value to toMs(monotonicNanos() - start)
    }

    private fun toMs(nanos: Long): Long = (nanos + NANOS_PER_MS / 2) / NANOS_PER_MS

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val ID_DIGITS = 12
    }
}
