package io.sonora.core.analysiscache

import android.database.sqlite.SQLiteException
import android.util.Log
import io.sonora.core.analysiscache.db.AnalysisCacheDao
import io.sonora.core.analysiscache.db.AnalysisEntity
import io.sonora.core.domain.analysis.AnalysisCache
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import kotlin.coroutines.cancellation.CancellationException

/**
 * [AnalysisCache] in its own Room database, bounded to [maxBytes] of entries by evicting the least recently used ones
 * (a read counts as a use).
 *
 * The eviction order is the order of use, whatever the clock does: a use stamped while the clock is behind the latest
 * stored use counts as one millisecond after it. A failure to record a read as a use is logged and the analysis is
 * still returned (the row stays; only its place in that order is stale).
 *
 * Disk use is dominated by the detail waveform, 150 bytes per second of audio: about 45 kB for a 5-minute track and
 * 90 kB for a 10-minute one, plus about 1 kB for everything else. The default bound of [DEFAULT_MAX_BYTES] (256 MiB)
 * holds about 5,000 tracks of 5 to 6 minutes (2,900 of 10 minutes). One entry is never larger than [MAX_ENTRY_BYTES]
 * (about 2 h 50 min of audio), so it always fits the 2 MB cursor window Android reads a row through; longer audio is
 * simply not cached.
 *
 * A row that does not make a valid analysis (or cannot be read back) is deleted and reported as a miss.
 */
class RoomAnalysisCache(
    private val dao: AnalysisCacheDao,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AnalysisCache {

    init {
        require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
    }

    override suspend fun get(contentSha256: String, analyzerVersion: String): TrackAnalysis? {
        val analysis = try {
            dao.find(contentSha256, analyzerVersion)?.toAnalysis() ?: return null
        } catch (e: IllegalArgumentException) {
            drop(contentSha256, analyzerVersion, e)
            return null
        } catch (e: SQLiteException) {
            // A row that cannot be read back (larger than the cursor window): the database itself is fine.
            drop(contentSha256, analyzerVersion, e)
            return null
        }
        try {
            dao.touch(contentSha256, analyzerVersion, nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The row was read and is valid: only its place in the eviction order is stale.
            Log.w(TAG, "Could not mark a cached analysis used ($analyzerVersion, $contentSha256)", e)
        }
        return analysis
    }

    override suspend fun put(contentSha256: String, analysis: TrackAnalysis) {
        val size = sizeOf(analysis, analysis.analyzerVersion)
        if (size > MAX_ENTRY_BYTES || size > maxBytes) {
            Log.i(TAG, "Not caching an analysis of $size bytes (${analysis.durationMs} ms of audio)")
            return
        }
        val waveform = analysis.waveform
        val now = nowMs()
        // Not in the same transaction as the insert: two puts at once may share a stamp (and usually created_at_ms
        // too), so their eviction order is undefined; either is a valid entry to evict.
        val used = dao.useStamp(now)
        val entity = AnalysisEntity(
            contentSha256 = contentSha256,
            analyzerVersion = analysis.analyzerVersion,
            bpm = analysis.beatGrid.bpm,
            firstBeatMs = analysis.beatGrid.firstBeatMs,
            firstBeatInBar = analysis.beatGrid.firstBeatInBar,
            beatCount = analysis.beatGrid.beatCount,
            keyIndex = analysis.key?.index,
            durationMs = analysis.durationMs,
            waveformDetail = waveform.detail(),
            waveformPreview = waveform.preview(),
            waveformTinyPreview = waveform.tinyPreview(),
            sizeBytes = size,
            createdAtMs = now,
            lastUsedMs = used,
        )
        dao.putWithin(entity, maxBytes)
    }

    override suspend fun remove(contentSha256: String, analyzerVersion: String) =
        dao.delete(contentSha256, analyzerVersion)

    override suspend fun prune(keepAnalyzerVersion: String) {
        val removed = dao.deleteOtherVersions(keepAnalyzerVersion)
        if (removed > 0) Log.i(TAG, "Pruned $removed analyses of other analyser versions")
    }

    private suspend fun drop(contentSha256: String, analyzerVersion: String, cause: Exception) {
        Log.w(TAG, "Dropping an unreadable cached analysis ($analyzerVersion, $contentSha256)", cause)
        dao.delete(contentSha256, analyzerVersion)
    }

    /** @throws IllegalArgumentException when the row does not make a valid analysis. */
    private fun AnalysisEntity.toAnalysis(): TrackAnalysis {
        require(durationMs >= 0) { "durationMs must not be negative: $durationMs" }
        val key = keyIndex?.let { MusicalKey.fromIndex(it) ?: throw IllegalArgumentException("Unknown key index $it") }
        return TrackAnalysis(
            beatGrid = BeatGrid(bpm, firstBeatMs, firstBeatInBar, beatCount),
            key = key,
            waveform = BlueWaveform(waveformDetail, waveformPreview, waveformTinyPreview),
            durationMs = durationMs,
            analyzerVersion = analyzerVersion,
        )
    }

    companion object {
        private const val TAG = "SonoraAnalysisCache"

        /** 256 MiB: about 5,000 tracks of 5 to 6 minutes. */
        const val DEFAULT_MAX_BYTES: Long = 256L shl 20

        /** 1.5 MiB, well inside the 2 MB cursor window a row is read through. */
        const val MAX_ENTRY_BYTES: Long = 3L shl 19

        /** Key, numbers and SQLite record overhead of a row, besides the waveforms and the version. */
        private const val ROW_OVERHEAD_BYTES = 256L

        /** What an entry of [analysis] counts against the size bound: about its size on disk. */
        fun sizeOf(analysis: TrackAnalysis, analyzerVersion: String): Long =
            analysis.waveform.detailSize.toLong() + BlueWaveform.PREVIEW_COLUMNS + BlueWaveform.TINY_PREVIEW_COLUMNS +
                analyzerVersion.length + ROW_OVERHEAD_BYTES
    }
}
