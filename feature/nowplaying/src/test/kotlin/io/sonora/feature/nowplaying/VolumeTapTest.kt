package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeTapTest {

    @Test
    fun `the loud speaker raises the volume by a tenth, up to the maximum`() {
        assertThat(louder(0f)).isWithin(1e-6f).of(0.1f)
        assertThat(louder(0.55f)).isWithin(1e-6f).of(0.65f)
        assertThat(louder(0.95f)).isEqualTo(1f)
        assertThat(louder(1f)).isEqualTo(1f)
    }
}
