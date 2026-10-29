package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.AbLoop
import org.junit.Test

class AbLoopMarkersTest {

    @Test
    fun `no markers while the loop is off or has no points`() {
        assertThat(loopMarkers(AbLoop(), 100_000)).isEmpty()
        assertThat(loopMarkers(AbLoop(enabled = true), 100_000)).isEmpty()
    }

    @Test
    fun `A alone, then A and B, as fractions of the track`() {
        assertThat(loopMarkers(AbLoop(enabled = true, startMs = 25_000, itemId = 1), 100_000)).containsExactly(0.25f)
        assertThat(loopMarkers(AbLoop(enabled = true, startMs = 25_000, endMs = 50_000, itemId = 1), 100_000))
            .containsExactly(0.25f, 0.5f).inOrder()
    }

    @Test
    fun `no markers without a duration`() {
        assertThat(loopMarkers(AbLoop(enabled = true, startMs = 25_000, endMs = 50_000), 0)).isEmpty()
    }
}
