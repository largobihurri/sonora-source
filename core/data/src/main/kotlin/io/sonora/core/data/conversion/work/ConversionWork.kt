package io.sonora.core.data.conversion.work

import androidx.work.Data
import androidx.work.workDataOf
import io.sonora.core.domain.model.BatchProgress

/** Names and data keys shared by [ConversionBatchWorker] and [WorkManagerConversionScheduler]. */
internal object ConversionWork {
    const val KEY_BATCH_ID = "batch_id"
    const val KEY_TASK = "task"
    const val TASK_CONVERT = "convert"
    const val TASK_REPLACE = "replace_originals"
    const val TAG = "sonora-conversion"

    private const val KEY_FINISHED = "finished"
    private const val KEY_TOTAL = "total"
    private const val KEY_CURRENT = "current_file"
    private const val KEY_FRACTION = "fraction"
    private const val KEY_FAILED = "failed"

    /** One unique job per batch: a second conversion request is ignored, a replacement runs after it. */
    fun uniqueName(batchId: Long) = "conversion-batch-$batchId"

    fun progressData(progress: BatchProgress): Data = workDataOf(
        KEY_FINISHED to progress.finished,
        KEY_TOTAL to progress.total,
        KEY_CURRENT to progress.currentFileName,
        KEY_FRACTION to progress.fraction,
        KEY_FAILED to progress.failed,
    )

    /** The progress a running job published, `null` before its first report. */
    fun progressOf(data: Data): BatchProgress? {
        if (!data.hasKeyWithValueOfType(KEY_TOTAL, Int::class.javaObjectType)) return null
        return BatchProgress(
            finished = data.getInt(KEY_FINISHED, 0),
            total = data.getInt(KEY_TOTAL, 0),
            currentFileName = data.getString(KEY_CURRENT),
            fraction = data.getFloat(KEY_FRACTION, 0f),
            failed = data.getInt(KEY_FAILED, 0),
        )
    }
}
