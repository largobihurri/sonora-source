package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImeHideDetectorTest {

    @Test
    fun `a keyboard not shown yet releases nothing, so a field just focused keeps its focus`() {
        val detector = ImeHideDetector()

        assertThat(detector.update(visible = false)).isFalse()
        assertThat(detector.update(visible = false)).isFalse()
    }

    @Test
    fun `the keyboard going away releases the field once`() {
        val detector = ImeHideDetector()
        detector.update(visible = false)

        assertThat(detector.update(visible = true)).isFalse()
        assertThat(detector.update(visible = false)).isTrue()
        assertThat(detector.update(visible = false)).isFalse()
    }

    @Test
    fun `each time the keyboard shows and goes away again, it releases the field again`() {
        val detector = ImeHideDetector()

        assertThat(detector.update(visible = true)).isFalse()
        assertThat(detector.update(visible = false)).isTrue()
        assertThat(detector.update(visible = true)).isFalse()
        assertThat(detector.update(visible = true)).isFalse()
        assertThat(detector.update(visible = false)).isTrue()
    }
}
