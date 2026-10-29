package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadoutFitTest {

    @Test
    fun `the shape of a readout has every digit as 0, which is what fixes its width with tabular figures`() {
        assertThat(ReadoutFit.shapeOf("155,0 BPM · 6B · +0,25 %")).isEqualTo("000,0 BPM · 0B · +0,00 %")
        assertThat(ReadoutFit.shapeOf("−— BPM")).isEqualTo("−— BPM")
        // The same shape for every step of a drag that keeps its digits' count.
        assertThat(ReadoutFit.shapeOf("128.0 BPM · 8A · +1.25%")).isEqualTo(ReadoutFit.shapeOf("127.9 BPM · 8A · +9.99%"))
        assertThat(ReadoutFit.shapeOf("99.9 BPM")).isNotEqualTo(ReadoutFit.shapeOf("100.0 BPM"))
    }

    @Test
    fun `the largest size on the grid that fits the width is chosen`() {
        // A line 10 px wide for every sp of its size, in 100 px.
        assertThat(ReadoutFit.largestFitting(minSize = 8f, maxSize = 36f, step = 0.5f, available = 100f) { it * 10f }).isEqualTo(10f)
        assertThat(ReadoutFit.largestFitting(minSize = 28f, maxSize = 36f, step = 0.5f, available = 345f) { it * 10f }).isEqualTo(34.5f)
        // Between two steps, it rounds down.
        assertThat(ReadoutFit.largestFitting(minSize = 28f, maxSize = 36f, step = 0.5f, available = 347f) { it * 10f }).isEqualTo(34.5f)
    }

    @Test
    fun `it is the largest size when everything fits and the smallest when nothing does`() {
        assertThat(ReadoutFit.largestFitting(minSize = 28f, maxSize = 36f, step = 0.5f, available = 1_000f) { it * 10f }).isEqualTo(36f)
        assertThat(ReadoutFit.largestFitting(minSize = 28f, maxSize = 36f, step = 0.5f, available = 100f) { it * 10f }).isEqualTo(28f)
    }

    @Test
    fun `a maximum below the minimum gives the minimum, as the readout always has`() {
        assertThat(ReadoutFit.largestFitting(minSize = 28f, maxSize = 20f, step = 0.5f, available = 1_000f) { it * 10f }).isEqualTo(28f)
    }

    @Test
    fun `it measures a handful of sizes, not every one on the grid`() {
        var measured = 0
        ReadoutFit.largestFitting(minSize = 28f, maxSize = 36f, step = 0.5f, available = 330f) {
            measured++
            it * 10f
        }
        assertThat(measured).isAtMost(6) // 17 sizes on the grid
    }
}
