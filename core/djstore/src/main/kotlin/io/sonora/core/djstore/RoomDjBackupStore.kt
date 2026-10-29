package io.sonora.core.djstore

import android.util.Log
import io.sonora.core.djengine.codec.DjAnalysisCodec
import io.sonora.core.djengine.codec.DjBackupRecord
import io.sonora.core.djengine.codec.DjCodecException
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djstore.db.DjAnalysisDao
import io.sonora.core.domain.backup.DjRestoreOutcome
import io.sonora.core.domain.port.DjBackupStore
import java.util.Base64

/**
 * The DJ store's part of the app backup (backup format version 2): one base64 text record per analysed track, each an
 * SDJ1 backup record (`DjAnalysisCodec.encodeBackup`). The backup never carries waveforms, which are recomputed from
 * the audio, so a record is about 160 bytes with a one-segment grid and 20,000 tracks are about 4.5 MB in the file.
 *
 * ## The duration
 *
 * `DjBackupRecord` has a sample rate and a frame count, but the store keeps the track's duration in milliseconds and
 * the SDJ1 record is frozen. The duration is therefore written as a count of 1 ms frames: [DURATION_FRAMES_PER_SECOND]
 * (1000) and `totalFrames = durationMs`. Restoring converts any record by `frames * 1000 / sampleRate`, so a record
 * with a real sample rate (a later writer) reads correctly too.
 *
 * ## Grid edits and cues
 *
 * With a [RoomDjUserDataBackup] the list also carries one `sdju1:` record per track that has a grid edit or cues,
 * after the analysis records; restoring routes each record to the part that understands it. A restore into a store
 * that was never given the part (or a build that does not know it) counts those records as skipped.
 *
 * ## Export
 *
 * Reads the table in pages of [PAGE_SIZE] rows without their waveforms, in audio id order. A row this build cannot read
 * (written by a later build, or damaged) is left out and counted in one log line; it stays in the store untouched.
 *
 * ## Restore
 *
 * Each record goes through [DjAnalysisStore.putIfAbsent]: an analysis the store already has is never overwritten (a
 * damaged row is repaired, as the store always does), and a restored analysis is stored without its waveform, so
 * `DjAnalysis.waveformMissing` is true for it until the analyser fills it. A record that is not base64, not a valid
 * SDJ1 backup record, from a later codec version or with a duration that overflows is skipped, and the skipped
 * records are counted in the result and in one warning: never a failed restore.
 */
class RoomDjBackupStore(
    private val dao: DjAnalysisDao,
    private val store: DjAnalysisStore,
    private val userData: RoomDjUserDataBackup? = null,
) : DjBackupStore {

    override suspend fun export(): List<String> {
        val records = ArrayList<String>()
        var left = 0
        var after = ""
        while (true) {
            val page = dao.pageWithoutWaveforms(after, PAGE_SIZE)
            for (row in page) {
                val state = AnalysisRows.read(row, decodeWaveform = false)
                val id = AudioId.parse(row.audioId)
                if (state is RowState.Readable && id != null) {
                    records += encode(id, state.analysis)
                } else {
                    left++
                }
            }
            if (page.size < PAGE_SIZE) break
            after = page.last().audioId
        }
        if (left > 0) Log.w(TAG, "Backup: $left analyses left out because this build cannot read them; they stay in the store")
        userData?.let { records += it.export() }
        return records
    }

    override suspend fun restore(records: List<String>): DjRestoreOutcome {
        var restored = 0
        var present = 0
        var skipped = 0
        for (text in records) {
            if (userData != null && text.startsWith(RoomDjUserDataBackup.PREFIX)) {
                when (userData.restore(text)) {
                    RoomDjUserDataBackup.Result.ADDED -> restored++
                    RoomDjUserDataBackup.Result.ALREADY_PRESENT -> present++
                    null -> skipped++
                }
                continue
            }
            val decoded = decode(text)
            if (decoded == null) {
                skipped++
            } else if (store.putIfAbsent(decoded.first, decoded.second)) {
                restored++
            } else {
                present++
            }
        }
        if (skipped > 0) Log.w(TAG, "Restore: $skipped of ${records.size} DJ records could not be read and were skipped")
        return DjRestoreOutcome(restored = restored, alreadyPresent = present, skipped = skipped)
    }

    private fun encode(id: AudioId, analysis: DjAnalysis): String {
        val record = DjBackupRecord(
            audioId = id,
            sampleRate = DURATION_FRAMES_PER_SECOND,
            totalFrames = analysis.durationMs,
            grid = analysis.grid,
            key = analysis.key,
            provenance = analysis.provenance,
        )
        return Base64.getEncoder().encodeToString(DjAnalysisCodec.encodeBackup(record))
    }

    /** The audio id and the waveform-less analysis a record stands for, or `null` when it cannot be read. */
    private fun decode(text: String): Pair<AudioId, DjAnalysis>? {
        val record = try {
            DjAnalysisCodec.decodeBackup(Base64.getDecoder().decode(text))
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: DjCodecException) {
            return null
        }
        val durationMs = try {
            Math.multiplyExact(record.totalFrames, MILLIS_PER_SECOND) / record.sampleRate
        } catch (_: ArithmeticException) {
            return null
        }
        return record.audioId to DjAnalysis(durationMs, record.grid, record.key, BandWaveform.MISSING, record.provenance)
    }

    companion object {
        /** The frame rate the duration is written at: one frame per millisecond. */
        const val DURATION_FRAMES_PER_SECOND = 1000

        /** Rows per page of the export: a page of rows without waveforms is far below the 2 MB cursor window. */
        const val PAGE_SIZE = 200

        private const val MILLIS_PER_SECOND = 1000L
        private const val TAG = "SonoraDjStore"
    }
}
