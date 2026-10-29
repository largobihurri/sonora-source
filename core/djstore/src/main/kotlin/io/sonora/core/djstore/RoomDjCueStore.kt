package io.sonora.core.djstore

import android.util.Log
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCueStore
import io.sonora.core.djmodel.DjCues
import io.sonora.core.djstore.db.CueDao
import io.sonora.core.djstore.db.CueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The permanent [DjCueStore] in Room (`sonora-dj.db`, table `cues`, added by schema version 3). It never deletes a row
 * on its own: a row goes only through [delete], which the user asks for.
 *
 * A row this build cannot read (written by a later build, or holding a kind, pad, position or colour outside what a
 * `DjCue` allows) is left out of the list and kept in the store byte for byte; a cue saved with the same id replaces it.
 */
class RoomDjCueStore(
    private val dao: CueDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DjCueStore {

    override suspend fun get(id: AudioId): List<DjCue> = readable(dao.forAudio(id.value))

    override fun observe(id: AudioId): Flow<List<DjCue>> = dao.observe(id.value)
        // Any write to the table re-runs the query: skip identical rows before reading them.
        .distinctUntilChanged()
        .map(::readable)
        .distinctUntilChanged()

    override suspend fun put(id: AudioId, cue: DjCue) {
        dao.upsert(
            CueEntity(
                audioId = id.value,
                cueId = cue.id,
                kind = if (cue.kind == CueKind.HOT) CueEntity.KIND_HOT else CueEntity.KIND_MEMORY,
                slot = cue.slot,
                positionMs = cue.positionMs,
                loopEndMs = cue.loopEndMs,
                colorIndex = cue.color.paletteIndex,
                colorRgb = cue.color.rgb,
                name = cue.name,
                updatedAtMs = clock(),
                recordVersion = CueEntity.RECORD_VERSION,
            ),
        )
    }

    override suspend fun delete(id: AudioId, cueId: String) = dao.delete(id.value, cueId)

    private fun readable(rows: List<CueEntity>): List<DjCue> {
        val cues = ArrayList<DjCue>(rows.size)
        for (row in rows) read(row)?.let(cues::add)
        return DjCues.sorted(cues)
    }

    private fun read(row: CueEntity): DjCue? {
        if (row.recordVersion > CueEntity.RECORD_VERSION) {
            Log.i(TAG, "No cue: ${row.cueId} of ${row.audioId} comes from a later build; kept as it is")
            return null
        }
        val kind = when (row.kind) {
            CueEntity.KIND_HOT -> CueKind.HOT
            CueEntity.KIND_MEMORY -> CueKind.MEMORY
            else -> {
                Log.w(TAG, "No cue: ${row.cueId} of ${row.audioId} has the unknown kind ${row.kind}; kept as it is")
                return null
            }
        }
        return try {
            DjCue(row.cueId, kind, row.slot, row.positionMs, row.loopEndMs, CueColor(row.colorIndex, row.colorRgb), row.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No cue: ${row.cueId} of ${row.audioId} holds an impossible value; kept as it is", e)
            null
        }
    }

    private companion object {
        const val TAG = "SonoraDjStore"
    }
}
