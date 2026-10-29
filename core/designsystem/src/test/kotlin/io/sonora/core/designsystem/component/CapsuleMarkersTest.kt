package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapsuleMarkersTest {

    @Test
    fun `a touch within reach of a marker grabs it`() {
        assertThat(markerUnder(x = 205f, width = 1_000f, markers = listOf(0.2f, 0.6f), reach = 24f, towards = 1f)).isEqualTo(0)
        assertThat(markerUnder(x = 580f, width = 1_000f, markers = listOf(0.2f, 0.6f), reach = 24f, towards = 1f)).isEqualTo(1)
    }

    @Test
    fun `a touch out of reach of every marker grabs none`() {
        assertThat(markerUnder(x = 400f, width = 1_000f, markers = listOf(0.2f, 0.6f), reach = 24f, towards = 1f)).isNull()
        assertThat(markerUnder(x = 400f, width = 1_000f, markers = emptyList(), reach = 24f, towards = 1f)).isNull()
    }

    @Test
    fun `between two close markers the nearest wins`() {
        assertThat(markerUnder(x = 506f, width = 1_000f, markers = listOf(0.5f, 0.51f), reach = 24f, towards = 1f)).isEqualTo(1)
        assertThat(markerUnder(x = 504f, width = 1_000f, markers = listOf(0.5f, 0.51f), reach = 24f, towards = 1f)).isEqualTo(0)
    }

    @Test
    fun `markers on the same spot go with the drag's direction`() {
        val markers = listOf(0.6f, 0.6005f)
        assertThat(markerUnder(x = 600f, width = 1_000f, markers = markers, reach = 24f, towards = 1f)).isEqualTo(1)
        assertThat(markerUnder(x = 600f, width = 1_000f, markers = markers, reach = 24f, towards = -1f)).isEqualTo(0)
    }
}
