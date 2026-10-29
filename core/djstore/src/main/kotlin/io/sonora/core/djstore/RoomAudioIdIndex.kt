package io.sonora.core.djstore

import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.FileStamp
import io.sonora.core.djstore.db.AudioIdDao
import io.sonora.core.djstore.db.AudioIdEntity

/**
 * The permanent uri to audio-id memo in Room (table `audio_ids`). It only saves hashing a file again, so it can always
 * be rebuilt and is never backed up. A lookup with a [FileStamp] is a miss when the file's size or last-modified time
 * differs from the one remembered (or none was remembered); the stale row stays until the file is remembered again.
 */
class RoomAudioIdIndex(private val dao: AudioIdDao) : AudioIdIndex {

    override suspend fun idFor(sourceUri: String, stamp: FileStamp?): AudioId? {
        val row = dao.find(sourceUri) ?: return null
        if (stamp != null && (row.sizeBytes != stamp.sizeBytes || row.lastModifiedMs != stamp.lastModifiedMs)) return null
        return AudioId.parse(row.audioId)
    }

    override suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp?) {
        dao.upsert(AudioIdEntity(sourceUri, stamp?.sizeBytes, stamp?.lastModifiedMs, id.value))
    }
}
