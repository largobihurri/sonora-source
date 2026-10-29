package io.sonora.core.transcoder

import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.transcoder.android.OutputTargets
import io.sonora.core.transcoder.android.PriorityDispatchers
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * [AudioConverter] on the [ConversionEngine]: takes the current budget for the requested mode (block size, thread
 * priority), opens the output document and runs the engine on a thread of that priority. The budget's concurrency
 * and pauses are for the batch that calls this (T7).
 */
class TranscoderAudioConverter @Inject constructor(
    private val engine: ConversionEngine,
    private val budgets: ConversionBudgetSource,
    private val outputs: OutputTargets,
    private val dispatchers: PriorityDispatchers,
) : AudioConverter {

    override suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit): ConversionResult {
        val budget = budgets.budgets(request.performanceMode).first()
        return withContext(dispatchers.forPriority(budget.threadPriority)) {
            outputs.open(request.outputUri).use { target ->
                val result = engine.convert(request.sourceUri, request.profile, target.channel, budget.chunkFrames, onProgress)
                target.commit()
                result
            }
        }
    }

    override suspend fun verify(outputUri: String, expected: ConversionResult): OutputVerification =
        engine.verifyDecodes(outputUri, expected)
}
