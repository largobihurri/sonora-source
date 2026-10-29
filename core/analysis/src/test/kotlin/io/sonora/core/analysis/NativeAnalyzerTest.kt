package io.sonora.core.analysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.AnalysisException
import org.junit.Assert.assertThrows
import org.junit.Test

/** On the JVM `libsonora_analysis.so` is absent, as on a device ABI the app ships no build for (32-bit ARM). */
class NativeAnalyzerTest {

    @Test
    fun `a missing native library is a typed engine failure, also on later attempts`() {
        repeat(2) {
            val failure = assertThrows(AnalysisException.EngineFailure::class.java) { NativeAnalyzer.create(44_100) }
            assertThat(failure.detail).contains("not available")
            assertThat(failure.cause).isInstanceOf(LinkageError::class.java)
        }
    }
}
