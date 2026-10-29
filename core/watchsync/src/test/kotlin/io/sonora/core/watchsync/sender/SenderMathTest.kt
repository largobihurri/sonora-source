package io.sonora.core.watchsync.sender

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SenderMathTest {

    @Test
    fun `the size estimate grows with the length and carries an allowance for the cover`() {
        val short = OpusSizeEstimate.bytesFor(60_000)
        val long = OpusSizeEstimate.bytesFor(240_000)

        assertThat(long).isGreaterThan(short)
        // 192 kbps is 24 000 bytes a second; the estimate is above that and below twice that.
        assertThat(OpusSizeEstimate.bytesFor(100_000)).isGreaterThan(2_400_000L)
        assertThat(OpusSizeEstimate.bytesFor(100_000)).isLessThan(4_800_000L)
        assertThat(OpusSizeEstimate.bytesFor(0)).isEqualTo(OpusSizeEstimate.COVER_ALLOWANCE_BYTES)
    }

    @Test
    fun `a negative duration estimates like zero`() {
        assertThat(OpusSizeEstimate.bytesFor(-5)).isEqualTo(OpusSizeEstimate.COVER_ALLOWANCE_BYTES)
    }

    @Test
    fun `a resume offset is valid from zero to the whole size`() {
        assertThat(ResumeOffsets.isValid(0, 100)).isTrue()
        assertThat(ResumeOffsets.isValid(60, 100)).isTrue()
        assertThat(ResumeOffsets.isValid(100, 100)).isTrue()
        assertThat(ResumeOffsets.isValid(-1, 100)).isFalse()
        assertThat(ResumeOffsets.isValid(101, 100)).isFalse()
    }

    @Test
    fun `encoding is the first share of a song's progress and sending the rest`() {
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.ENCODING, 0f)).isEqualTo(0f)
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.ENCODING, 1f)).isWithin(1e-6f).of(SendProgressMath.ENCODE_SHARE)
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.SENDING, 0f)).isWithin(1e-6f).of(SendProgressMath.ENCODE_SHARE)
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.SENDING, 1f)).isEqualTo(1f)
    }

    @Test
    fun `the fraction of a file is bytes over size and never NaN`() {
        assertThat(SendProgressMath.fraction(done = 50, total = 200)).isEqualTo(0.25f)
        assertThat(SendProgressMath.fraction(done = 300, total = 200)).isEqualTo(1f)
        assertThat(SendProgressMath.fraction(done = -5, total = 200)).isEqualTo(0f)
        assertThat(SendProgressMath.fraction(done = 0, total = 0)).isEqualTo(1f)
        assertThat(SendProgressMath.fraction(done = 10, total = -1)).isEqualTo(1f)
    }

    @Test
    fun `a phase fraction outside zero to one is clamped`() {
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.SENDING, 7f)).isEqualTo(1f)
        assertThat(SendProgressMath.songFraction(SendProgress.Phase.ENCODING, -3f)).isEqualTo(0f)
    }

    @Test
    fun `overall progress weighs songs by their length`() {
        val weights = listOf(10L, 30L)

        assertThat(SendProgressMath.overall(weights, finished = 0, currentSongFraction = 0f)).isEqualTo(0f)
        assertThat(SendProgressMath.overall(weights, finished = 1, currentSongFraction = 0.5f)).isWithin(1e-6f).of(0.625f)
        assertThat(SendProgressMath.overall(weights, finished = 2, currentSongFraction = 0f)).isEqualTo(1f)
    }

    @Test
    fun `overall progress of nothing is complete and zero lengths still count`() {
        assertThat(SendProgressMath.overall(emptyList(), 0, 0f)).isEqualTo(1f)
        assertThat(SendProgressMath.overall(listOf(0L, 0L), finished = 1, currentSongFraction = 0f)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `throughput is bytes over seconds and unknown without time`() {
        val report = SendReport(
            sent = listOf(
                SentTrack("a", "A", sizeBytes = 5_000_000, transferredBytes = 4_000_000, elapsedMs = 20_000, resumedFrom = 1_000_000),
                SentTrack("b", "B", sizeBytes = 6_000_000, transferredBytes = 6_000_000, elapsedMs = 30_000, resumedFrom = 0),
            ),
            skipped = emptyList(),
            failed = emptyList(),
            attempts = 1,
        )

        assertThat(report.transferredBytes).isEqualTo(10_000_000L)
        assertThat(report.bytesPerSecond).isWithin(0.5).of(200_000.0)
        assertThat(SendReport(emptyList(), emptyList(), emptyList(), 1).bytesPerSecond).isNull()
    }
}
