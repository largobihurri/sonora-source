package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import java.io.File
import java.util.Locale
import org.junit.Test

class DjStatusTextTest {

    private fun analysing(progress: Float) = AnalysisStatus.Analysing(AnalysisStage.entries.first(), progress)

    @Test
    fun `no status yet and not analysed both read as waiting`() {
        assertThat(DjStatusText.chip(null)).isEqualTo(DjChip.Waiting)
        assertThat(DjStatusText.chip(AnalysisStatus.NotAnalysed)).isEqualTo(DjChip.Waiting)
        assertThat(DjStatusText.chip(AnalysisStatus.Queued)).isEqualTo(DjChip.Waiting)
    }

    @Test
    fun `analysed and failed have their own chips`() {
        assertThat(DjStatusText.chip(AnalysisStatus.Analysed)).isEqualTo(DjChip.Analysed)
        assertThat(DjStatusText.chip(AnalysisStatus.Failed("decode"))).isEqualTo(DjChip.Failed)
    }

    @Test
    fun `progress is floored so the chip never claims more than is done`() {
        assertThat(DjStatusText.chip(analysing(0.0f))).isEqualTo(DjChip.Analysing(0))
        assertThat(DjStatusText.chip(analysing(0.426f))).isEqualTo(DjChip.Analysing(42))
        assertThat(DjStatusText.chip(analysing(0.5f))).isEqualTo(DjChip.Analysing(50))
    }

    @Test
    fun `progress stays within 0 to 99 until the analysis is done`() {
        assertThat(DjStatusText.chip(analysing(0.999f))).isEqualTo(DjChip.Analysing(99))
        assertThat(DjStatusText.chip(analysing(1.0f))).isEqualTo(DjChip.Analysing(99))
        assertThat(DjStatusText.chip(analysing(7f))).isEqualTo(DjChip.Analysing(99))
        assertThat(DjStatusText.chip(analysing(-0.3f))).isEqualTo(DjChip.Analysing(0))
        assertThat(DjStatusText.chip(analysing(Float.NaN))).isEqualTo(DjChip.Analysing(0))
    }

    @Test
    fun `the label is DJ, with the percentage in the locale's style while analysing`() {
        assertThat(DjStatusText.label(DjChip.Waiting, Locale.US)).isEqualTo("DJ")
        assertThat(DjStatusText.label(DjChip.Analysed, Locale.US)).isEqualTo("DJ")
        assertThat(DjStatusText.label(DjChip.Failed, Locale.US)).isEqualTo("DJ !")
        assertThat(DjStatusText.label(DjChip.Analysing(42), Locale.US)).isEqualTo("DJ 42%")
        assertThat(DjStatusText.label(DjChip.Analysing(42), Locale.forLanguageTag("es-ES"))).isEqualTo("DJ 42 %")
        assertThat(DjStatusText.label(DjChip.Analysing(42), Locale.forLanguageTag("eu"))).isEqualTo("DJ % 42")
    }

    private fun strings(qualifier: String) = XmlStrings(listOf(R::class.java to File("src/main/res")), qualifier)

    @Test
    fun `every chip has a full description in each language`() {
        for (qualifier in listOf("", "-es", "-eu")) {
            val strings = strings(qualifier)
            val descriptions = listOf(DjChip.Waiting, DjChip.Analysed, DjChip.Failed, DjChip.Analysing(42))
                .map { DjStatusText.description(it, strings) }

            assertThat(descriptions.toSet()).hasSize(descriptions.size)
            assertThat(descriptions.all { it.isNotBlank() }).isTrue()
        }
    }

    @Test
    fun `the analysing description names the percentage`() {
        val text = DjStatusText.description(DjChip.Analysing(42), strings(""))

        assertThat(text).contains("42%")
    }
}
