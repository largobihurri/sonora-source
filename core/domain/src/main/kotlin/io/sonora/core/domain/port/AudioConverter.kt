package io.sonora.core.domain.port

import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification

/**
 * Converts one audio file according to a [io.sonora.core.domain.conversion.ConversionProfile]. Batches, the
 * concurrency from [ConversionBudgetSource], temporary outputs and their final move are the caller's job.
 */
interface AudioConverter {

    /**
     * Decodes [ConversionRequest.sourceUri] and writes the converted file to [ConversionRequest.outputUri].
     * [onProgress] receives the decoded fraction in `[0, 1]` (only when the source's length is known) and 1 at the
     * end. Cancelling the calling coroutine stops the conversion and leaves a partial output for the caller to
     * delete.
     *
     * @throws io.sonora.core.domain.audio.PcmDecodeException when the source cannot be decoded.
     * @throws java.io.IOException when the output cannot be written.
     * @throws UnsupportedOutputException when nothing in this build writes the profile's container and sample format.
     */
    suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit = {}): ConversionResult

    /** Decodes [outputUri] completely and checks it matches [expected]. Never throws for bad files. */
    suspend fun verify(outputUri: String, expected: ConversionResult): OutputVerification
}

/**
 * Nothing in this build writes [profile]'s container and sample format (an output format whose encoder is not there
 * yet), or this device cannot ([reason], e.g. its encoder lacks a setting the profile asks for). Open, so a converter's
 * own "no encoder" error can be one; the batch fails only that file.
 */
open class UnsupportedOutputException(val profile: ConversionProfile, val reason: String? = null) :
    IllegalArgumentException("No encoder for ${profile.container} ${profile.sampleFormat} (profile ${profile.id})")
