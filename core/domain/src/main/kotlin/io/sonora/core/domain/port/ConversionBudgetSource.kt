package io.sonora.core.domain.port

import io.sonora.core.domain.conversion.ConversionBudget
import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.flow.Flow

/**
 * The live [ConversionBudget] for a requested [PerformanceMode]: it steps down while the device is hot or in battery
 * saver and back up when it recovers. A batch reads it before starting each file.
 */
interface ConversionBudgetSource {
    fun budgets(mode: PerformanceMode): Flow<ConversionBudget>
}
