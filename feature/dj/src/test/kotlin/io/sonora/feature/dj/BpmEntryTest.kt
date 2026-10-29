package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.dj.BpmEntry.Problem
import org.junit.Test

class BpmEntryTest {

    private fun valid(text: String): Int? = (BpmEntry.parse(text) as? BpmEntry.Result.Valid)?.bpmHundredths

    private fun problem(text: String): Problem? = (BpmEntry.parse(text) as? BpmEntry.Result.Invalid)?.problem

    @Test
    fun `a whole number is that many BPM`() {
        assertThat(valid("128")).isEqualTo(12_800)
    }

    @Test
    fun `one and two decimals are kept exactly`() {
        assertThat(valid("128.5")).isEqualTo(12_850)
        assertThat(valid("128.25")).isEqualTo(12_825)
        assertThat(valid("127.99")).isEqualTo(12_799)
        assertThat(valid("90.05")).isEqualTo(9_005)
    }

    @Test
    fun `a comma is a decimal separator too`() {
        assertThat(valid("128,25")).isEqualTo(12_825)
        assertThat(valid("128,5")).isEqualTo(12_850)
    }

    @Test
    fun `spaces around the number are ignored and a trailing separator is a whole number`() {
        assertThat(valid("  128.50 ")).isEqualTo(12_850)
        assertThat(valid("128.")).isEqualTo(12_800)
        assertThat(valid("128,")).isEqualTo(12_800)
    }

    @Test
    fun `the range of a grid edit is accepted end to end`() {
        assertThat(valid("20")).isEqualTo(2_000)
        assertThat(valid("20.00")).isEqualTo(2_000)
        assertThat(valid("400")).isEqualTo(40_000)
        assertThat(valid("400.00")).isEqualTo(40_000)
    }

    @Test
    fun `a tempo outside the range is out of range`() {
        assertThat(problem("19.99")).isEqualTo(Problem.OutOfRange)
        assertThat(problem("400.01")).isEqualTo(Problem.OutOfRange)
        assertThat(problem("0")).isEqualTo(Problem.OutOfRange)
        assertThat(problem("0.00")).isEqualTo(Problem.OutOfRange)
        assertThat(problem("1000")).isEqualTo(Problem.OutOfRange)
    }

    @Test
    fun `a huge number is out of range and does not overflow`() {
        assertThat(problem("99999999999999999999999")).isEqualTo(Problem.OutOfRange)
        assertThat(problem("99999999999999999999999.99")).isEqualTo(Problem.OutOfRange)
    }

    @Test
    fun `nothing typed is empty`() {
        assertThat(problem("")).isEqualTo(Problem.Empty)
        assertThat(problem("   ")).isEqualTo(Problem.Empty)
    }

    @Test
    fun `text that is not a plain decimal number is not a number`() {
        for (text in listOf("abc", "12a", "-5", "+128", "1e2", "1.2.3", "1,2,3", "1.2,3", ".", ",", ".5", "12 8", "1 000", "128 BPM", "0x80")) {
            assertThat(problem(text)).isEqualTo(Problem.NotANumber)
        }
    }

    @Test
    fun `more than two decimals is rejected and not rounded`() {
        assertThat(problem("128.505")).isEqualTo(Problem.TooManyDecimals)
        assertThat(problem("128,001")).isEqualTo(Problem.TooManyDecimals)
        assertThat(problem("128.000")).isEqualTo(Problem.TooManyDecimals)
    }

    @Test
    fun `digits of other scripts are read as digits`() {
        assertThat(valid("١٢٨٫٥".replace('٫', '.'))).isEqualTo(12_850)
    }

    @Test
    fun `the range is the one of a grid edit`() {
        assertThat(BpmEntry.MIN_HUNDREDTHS).isEqualTo(io.sonora.core.djmodel.GridEdit.MIN_BPM_HUNDREDTHS)
        assertThat(BpmEntry.MAX_HUNDREDTHS).isEqualTo(io.sonora.core.djmodel.GridEdit.MAX_BPM_HUNDREDTHS)
    }
}
