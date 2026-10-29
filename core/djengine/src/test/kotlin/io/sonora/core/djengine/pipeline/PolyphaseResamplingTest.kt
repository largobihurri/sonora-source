package io.sonora.core.djengine.pipeline

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.resample.PolyphaseResampler
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.MonoSink
import org.junit.Test

class PolyphaseResamplingTest {

    /** Collects everything it is given. */
    private class Collector : MonoSink<FloatArray> {
        private val values = ArrayList<Float>()
        var finished = false

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            for (i in offset until offset + count) values += samples[i]
        }

        override fun finish(): FloatArray {
            finished = true
            return values.toFloatArray()
        }
    }

    @Test
    fun `streaming in uneven offset blocks equals one pass and delivers every frame`() {
        val input = SyntheticAudio.clickTrack(128.0, durationMs = 2_000.0)
        for ((from, to) in listOf(44_100 to 22_050, 48_000 to 22_050, 44_100 to 16_000, 22_050 to 44_100)) {
            val reference = PolyphaseResampler(from, to, 1)
            val whole = FloatArray(reference.maxOutputFrames(input.size))
            val n = reference.process(input, input.size, whole)
            val tail = FloatArray(reference.maxOutputFrames(0))
            val m = reference.flush(tail)
            val expected = whole.copyOf(n) + tail.copyOf(m)

            val collector = Collector()
            val stream = PolyphaseResampling.into(collector, from, to)
            val padded = FloatArray(input.size + 7).also { input.copyInto(it, 7) }
            var at = 0
            var block = 1
            while (at < input.size) {
                val count = minOf(block, input.size - at)
                stream.accept(padded, 7 + at, count)
                at += count
                block = block * 3 + 1
            }
            stream.finish()

            assertThat(collector.finished).isFalse()
            val actual = collector.finish()
            assertThat(actual.size.toLong()).isEqualTo(reference.outputFrameCount(input.size.toLong()))
            assertThat(actual).isEqualTo(expected)
        }
    }
}
