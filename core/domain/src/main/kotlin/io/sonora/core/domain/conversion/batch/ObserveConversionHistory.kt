package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.port.ConversionBatchRepository
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The converter history in date sections ([HistoryGrouping]), updated as batches run. */
class ObserveConversionHistory(private val repository: ConversionBatchRepository, private val clock: Clock) {
    operator fun invoke(): Flow<List<HistoryGroup>> = repository.batches().map { HistoryGrouping.group(it, clock) }
}
