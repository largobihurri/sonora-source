package io.sonora.core.djstore

import android.util.Log
import io.sonora.core.djengine.codec.DjAnalysisCodec
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djstore.db.DjAnalysisDao
import io.sonora.core.djstore.db.DjAnalysisEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The permanent [DjAnalysisStore] in Room (`sonora-dj.db`, table `analyses`). Nothing here ever deletes a row on its
 * own, and nothing discards an analysis because the analyser improved: an analysis is replaced only by [replace], which
 * the user asks for.
 *
 * ## What counts as an analysis
 *
 * A row is an analysis when this build can read all of it. Two other kinds of row are reported as "not analysed"
 * ([get] returns `null`, [analysedAmong] leaves the id out, [observe] emits `null`):
 * - a row written by a later build (a newer SDJ1 version in a blob, a newer row layout): it is NOT damaged, so it is
 *   kept byte for byte, and [putIfAbsent] never overwrites it (a build that reads it may be installed again);
 * - a damaged row (bad checksum, truncated blob, impossible value): it can never be read, so [putIfAbsent] repairs it
 *   by storing the new analysis in its place.
 *
 * Only [replace] overwrites a row whatever it holds, because it is an explicit request.
 *
 * ## Analyses restored from a backup
 *
 * A backup keeps no waveform (it is recomputed from the audio), so a restored analysis is stored with
 * `waveform_version` 0 and an SDJ1 waveform of no columns. [get] returns it with [BandWaveform.MISSING]
 * (`DjAnalysis.waveformMissing` is true), [analysedAmong] still counts it as analysed, [waveformMissingAmong] lists
 * such ids so the scheduler can queue only the waveform stage, and [fillMissingWaveform] completes the row in one
 * statement without touching the grid or the key. [replace] with a whole analysis also completes it.
 *
 * Reading decodes the blobs; the compressed waveform bytes depend on the zlib build, so two stored waveforms are
 * compared by their decoded content (as `DjAnalysis` equality does), never by blob bytes.
 *
 * A row is read whole through Android's 2 MB cursor window. A waveform of 150 columns per second compresses well below
 * that for any normal track, but a recording of several hours may not fit; reading such a row fails with the
 * database's own exception rather than returning a partial analysis.
 */
class RoomDjAnalysisStore(private val dao: DjAnalysisDao) : DjAnalysisStore {

    override suspend fun get(id: AudioId): DjAnalysis? = dao.find(id.value)?.let { readable(it) }

    override fun observe(id: AudioId): Flow<DjAnalysis?> = dao.observe(id.value)
        // Any write to the table re-runs the query: skip identical rows before paying to decode them.
        .distinctUntilChanged()
        .map { row -> row?.let { readable(it) } }
        .distinctUntilChanged()

    override suspend fun analysedAmong(ids: Collection<AudioId>): Set<AudioId> {
        if (ids.isEmpty()) return emptySet()
        val found = HashSet<String>()
        for (chunk in ids.distinct().chunked(MAX_IDS_PER_QUERY)) {
            found += dao.readableAmong(
                ids = chunk.map { it.value },
                maxRecordVersion = DjAnalysisEntity.RECORD_VERSION,
                magic = SDJ1_MAGIC,
                maxFormat = MAX_FORMAT_VERSION,
            )
        }
        return ids.filterTo(HashSet()) { it.value in found }
    }

    override suspend fun putIfAbsent(id: AudioId, analysis: DjAnalysis): Boolean =
        dao.putIfAbsent(AnalysisRows.toEntity(id, analysis)) { existing -> AnalysisRows.read(existing) is RowState.Damaged }

    override suspend fun replace(id: AudioId, analysis: DjAnalysis) {
        dao.upsert(AnalysisRows.toEntity(id, analysis))
    }

    override suspend fun waveformMissingAmong(ids: Collection<AudioId>): Set<AudioId> {
        if (ids.isEmpty()) return emptySet()
        val found = HashSet<String>()
        for (chunk in ids.distinct().chunked(MAX_IDS_PER_QUERY)) {
            found += dao.waveformMissingAmong(chunk.map { it.value }, DjAnalysisEntity.RECORD_VERSION)
        }
        return ids.filterTo(HashSet()) { it.value in found }
    }

    override suspend fun fillMissingWaveform(id: AudioId, waveform: BandWaveform): Boolean {
        require(waveform.columnCount > 0) { "A waveform without columns fills nothing" }
        return dao.fillWaveform(
            audioId = id.value,
            blob = DjAnalysisCodec.encodeWaveform(waveform),
            version = waveform.version,
            maxRecordVersion = DjAnalysisEntity.RECORD_VERSION,
        ) == 1
    }

    private fun readable(row: DjAnalysisEntity): DjAnalysis? = when (val state = AnalysisRows.read(row)) {
        is RowState.Readable -> state.analysis
        is RowState.Unsupported -> {
            Log.i(TAG, "Not analysed: the row of ${row.audioId} comes from a later build (${state.reason}); kept as it is")
            null
        }
        is RowState.Damaged -> {
            Log.w(TAG, "Not analysed: the row of ${row.audioId} is damaged (${state.reason})", state.cause)
            null
        }
    }

    private companion object {
        const val TAG = "SonoraDjStore"

        /** Well under SQLite's 999 variables of Android 10, which also take the other four parameters. */
        const val MAX_IDS_PER_QUERY = 500

        val SDJ1_MAGIC = byteArrayOf(0x53, 0x44, 0x4A, 0x31)

        /** The newest SDJ1 format version this build reads, as the two big-endian bytes the BLOBs carry. */
        val MAX_FORMAT_VERSION = byteArrayOf((DjAnalysisCodec.FORMAT_VERSION ushr 8).toByte(), DjAnalysisCodec.FORMAT_VERSION.toByte())
    }
}
