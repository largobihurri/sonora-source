package io.sonora.core.djengine.pipeline

import io.sonora.core.audiodsp.resample.PolyphaseResampler
import io.sonora.core.djmodel.MonoSink

/**
 * The [Resampler] of the pipeline: one mono [PolyphaseResampler] stream per call (linear phase, no delay: output
 * sample `m` sits at input time `m * inputRate / outputRate`, so the resampled stages stay aligned with the others to
 * the sample). The output buffer grows with the largest block and is reused, so a steady stream allocates nothing.
 */
object PolyphaseResampling : Resampler {

    override fun into(target: MonoSink<*>, inputRateHz: Int, outputRateHz: Int): MonoSink<Unit> =
        Stream(target, PolyphaseResampler(inputRateHz, outputRateHz, channelCount = 1))

    private class Stream(private val target: MonoSink<*>, private val resampler: PolyphaseResampler) : MonoSink<Unit> {
        private var input = FloatArray(0)
        private var output = FloatArray(resampler.maxOutputFrames(0))

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "range $offset+$count outside ${samples.size}" }
            val source = if (offset == 0) {
                samples
            } else {
                if (input.size < count) input = FloatArray(count)
                samples.copyInto(input, 0, offset, offset + count)
                input
            }
            val needed = resampler.maxOutputFrames(count)
            if (output.size < needed) output = FloatArray(needed)
            val produced = resampler.process(source, count, output)
            if (produced > 0) target.accept(output, 0, produced)
        }

        override fun finish() {
            val produced = resampler.flush(output)
            if (produced > 0) target.accept(output, 0, produced)
        }
    }
}
