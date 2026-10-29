package io.sonora.core.analysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.AnalysisException
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeResultsTest {

    @Test
    fun `parses the result layout of the JNI bridge`() {
        val result = NativeResults.parse(doubleArrayOf(128.0, 13_056.5, 1.0, 22.0, 2_646_000.0, 128.0))

        assertThat(result).isEqualTo(
            EngineResult(
                bpm = 128.0,
                firstBeatFrame = 13_056.5,
                firstBeatInBar = 1,
                keyIndex = 22,
                totalFrames = 2_646_000,
                detectedBeats = 128,
            ),
        )
    }

    @Test
    fun `rejects a result array of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) { NativeResults.parse(DoubleArray(5)) }
    }

    @Test
    fun `maps native status codes to analysis exceptions`() {
        val tooShort = NativeResults.failure(-6, sampleRateHz = 44_100, framesProcessed = 88_200)
        assertThat(tooShort).isInstanceOf(AnalysisException.TooShort::class.java)
        assertThat((tooShort as AnalysisException.TooShort).durationMs).isEqualTo(2_000)
        assertThat(tooShort.minimumMs).isEqualTo(5_000)

        assertThat(NativeResults.failure(-7, 44_100, 0)).isInstanceOf(AnalysisException.NoBeatsDetected::class.java)

        val rate = NativeResults.failure(-5, 4_000, 0)
        assertThat((rate as AnalysisException.UnsupportedSampleRate).sampleRateHz).isEqualTo(4_000)

        for (status in listOf(-1, -2, -3, -4, -99)) {
            val failure = NativeResults.failure(status, 44_100, 0)
            assertThat(failure).isInstanceOf(AnalysisException.EngineFailure::class.java)
            assertThat((failure as AnalysisException.EngineFailure).detail).contains("$status")
        }
        assertThat((NativeResults.failure(-3, 44_100, 0) as AnalysisException.EngineFailure).detail)
            .contains("out of memory")
    }
}
