package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.port.ConversionBatchRepository

/**
 * "Replace originals" for a finished batch, asked after it ran: every file whose verified output is still in the
 * batch folder ([io.sonora.core.domain.model.ConversionBatchItem.canReplaceOriginal], including earlier failed
 * replacements) is moved next to its original through [OriginalReplacer], one at a time. The background job calls it;
 * the UI asks for it through [io.sonora.core.domain.port.ConversionBatchScheduler.enqueueReplacement]. The batch
 * folder is deleted once that left it empty.
 */
class ReplaceBatchOriginals(
    private val repository: ConversionBatchRepository,
    private val replacer: OriginalReplacer,
) {

    /** The batch afterwards; unchanged while it is still converting, `null` when it does not exist. */
    suspend operator fun invoke(batchId: Long, onProgress: suspend (BatchProgress) -> Unit = {}): ConversionBatch? {
        val batch = repository.getBatch(batchId) ?: return null
        if (!batch.status.isFinal) return batch
        // Each copy is named after its own profile; a file whose profile is no longer known stays as it is.
        val items = repository.getItems(batchId).filter { it.canReplaceOriginal }
            .mapNotNull { item -> ConversionProfiles.byId(item.profileId)?.let { item to it } }
        var failed = 0
        items.forEachIndexed { index, (item, profile) ->
            onProgress(BatchProgress(index, items.size, item.sourceName, index.toFloat() / items.size, failed))
            val outcome = replacer.replace(profile, item)
            if (outcome.state == BatchItemState.FAILED) failed++
            repository.updateItem(outcome)
        }
        batch.outputFolderUri?.let { replacer.removeIfEmpty(it) }
        onProgress(BatchProgress(items.size, items.size, null, 1f, failed))
        return repository.getBatch(batchId)
    }
}
