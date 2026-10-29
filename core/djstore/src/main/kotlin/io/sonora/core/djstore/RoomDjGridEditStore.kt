package io.sonora.core.djstore

import android.util.Log
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djstore.db.GridEditDao
import io.sonora.core.djstore.db.GridEditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The permanent [DjGridEditStore] in Room (`sonora-dj.db`, table `grid_edits`, added by schema version 2). It never
 * deletes a row on its own: a row goes only through [delete], which the user asks for ("reset to analysis").
 *
 * A row this build cannot read (written by a later build, or holding a tempo or anchor outside what a `GridEdit`
 * allows) is reported as "no edit" and left in the store byte for byte; [put] replaces it, because saving an edit is
 * an explicit request.
 */
class RoomDjGridEditStore(
    private val dao: GridEditDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DjGridEditStore {

    override suspend fun get(id: AudioId): GridEdit? = dao.find(id.value)?.let(::readable)

    override fun observe(id: AudioId): Flow<GridEdit?> = dao.observe(id.value)
        // Any write to the table re-runs the query: skip identical rows before reading them.
        .distinctUntilChanged()
        .map { row -> row?.let(::readable) }
        .distinctUntilChanged()

    override suspend fun put(id: AudioId, edit: GridEdit) {
        dao.upsert(
            GridEditEntity(
                audioId = id.value,
                bpmHundredths = edit.bpmHundredths,
                anchorMs = edit.anchorMs,
                updatedAtMs = clock(),
                recordVersion = GridEditEntity.RECORD_VERSION,
            ),
        )
    }

    override suspend fun delete(id: AudioId) = dao.delete(id.value)

    private fun readable(row: GridEditEntity): GridEdit? {
        if (row.recordVersion > GridEditEntity.RECORD_VERSION) {
            Log.i(TAG, "No edit: the grid edit of ${row.audioId} comes from a later build; kept as it is")
            return null
        }
        return try {
            GridEdit(row.bpmHundredths, row.anchorMs)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No edit: the grid edit of ${row.audioId} holds an impossible value", e)
            null
        }
    }

    private companion object {
        const val TAG = "SonoraDjStore"
    }
}
