package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.audio.PcmDecodeException
import java.io.FileNotFoundException
import org.junit.Test

class AnalysisFailureKindTest {

    @Test
    fun `an unreadable source or file is unreadable`() {
        assertThat(AnalysisFailureKind.of(PcmDecodeException.SourceUnavailable("content://a"))).isEqualTo(AnalysisFailureKind.UNREADABLE)
        assertThat(AnalysisFailureKind.of(FileNotFoundException("gone"))).isEqualTo(AnalysisFailureKind.UNREADABLE)
        assertThat(AnalysisFailureKind.of(SecurityException("revoked"))).isEqualTo(AnalysisFailureKind.UNREADABLE)
    }

    @Test
    fun `a format nobody decodes is unsupported`() {
        assertThat(AnalysisFailureKind.of(PcmDecodeException.UnsupportedFormat("aiff-c"))).isEqualTo(AnalysisFailureKind.UNSUPPORTED)
        assertThat(AnalysisFailureKind.of(AnalysisException.UnsupportedSampleRate(1000))).isEqualTo(AnalysisFailureKind.UNSUPPORTED)
    }

    @Test
    fun `damaged audio is corrupt`() {
        assertThat(AnalysisFailureKind.of(PcmDecodeException.CorruptData("truncated"))).isEqualTo(AnalysisFailureKind.CORRUPT)
    }

    @Test
    fun `a clip that is too short says so`() {
        assertThat(AnalysisFailureKind.of(AnalysisException.TooShort(1_000, 4_000))).isEqualTo(AnalysisFailureKind.TOO_SHORT)
    }

    @Test
    fun `anything else is other`() {
        assertThat(AnalysisFailureKind.of(IllegalStateException("boom"))).isEqualTo(AnalysisFailureKind.OTHER)
        assertThat(AnalysisFailureKind.of(AnalysisException.EngineFailure("oom"))).isEqualTo(AnalysisFailureKind.OTHER)
    }

    @Test
    fun `a failed status defaults to other and keeps its reason`() {
        val failed = AnalysisStatus.Failed("boom")
        assertThat(failed.kind).isEqualTo(AnalysisFailureKind.OTHER)
        assertThat(failed).isEqualTo(AnalysisStatus.Failed("boom", AnalysisFailureKind.OTHER))
    }

    @Test
    fun `a reanalysis is a replacing run and a first analysis is not`() {
        assertThat(AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.5f).replacing).isFalse()
        assertThat(AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.5f, replacing = true).replacing).isTrue()
    }
}
