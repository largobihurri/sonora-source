package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TilePlanTest {

    private fun TilePlan.visible() = firstVisible..lastVisible

    private fun TilePlan.wanted() = firstWanted..lastWanted

    @Test
    fun `the visible tiles are those under the view centred on the playhead`() {
        val plan = TilePlan(tileCount = 100)
        // Centre 1000 on a 1080 px view: pixels [460, 1540) touch tiles 1 to 6.
        plan.wanted(centrePx = 1_000.0, widthPx = 1_080, direction = 0)
        assertThat(plan.visible()).isEqualTo(1..6)
    }

    @Test
    fun `playing forward prefetches two tiles ahead and none behind`() {
        val plan = TilePlan(tileCount = 100)
        plan.wanted(centrePx = 1_000.0, widthPx = 1_080, direction = 1)
        assertThat(plan.visible()).isEqualTo(1..6)
        assertThat(plan.wanted()).isEqualTo(1..8)
    }

    @Test
    fun `moving backwards prefetches two tiles behind`() {
        val plan = TilePlan(tileCount = 100)
        plan.wanted(centrePx = 5_000.0, widthPx = 1_080, direction = -1)
        // Pixels [4460, 5540) touch tiles 17 to 21.
        assertThat(plan.visible()).isEqualTo(17..21)
        assertThat(plan.wanted()).isEqualTo(15..21)
    }

    @Test
    fun `standing still prefetches one tile on each side`() {
        val plan = TilePlan(tileCount = 100)
        plan.wanted(centrePx = 5_000.0, widthPx = 1_080, direction = 0)
        assertThat(plan.wanted()).isEqualTo(16..22)
    }

    @Test
    fun `tiles before the track start are neither visible nor wanted`() {
        val plan = TilePlan(tileCount = 100)
        // At the start the left half of the view is before the track: pixels [-540, 540) touch tiles -3 to 2.
        plan.wanted(centrePx = 0.0, widthPx = 1_080, direction = 1)
        assertThat(plan.visible()).isEqualTo(0..2)
        assertThat(plan.wanted()).isEqualTo(0..4)
    }

    @Test
    fun `tiles after the track end are neither visible nor wanted`() {
        val plan = TilePlan(tileCount = 10)
        plan.wanted(centrePx = 2_500.0, widthPx = 1_080, direction = 1)
        assertThat(plan.visible()).isEqualTo(7..9)
        assertThat(plan.wanted()).isEqualTo(7..9)
    }

    @Test
    fun `a view wholly outside the track shows nothing but still prefetches the edge it moves toward`() {
        val plan = TilePlan(tileCount = 10)
        // Pixels [-1440, -360) touch tiles -6 to -2; two ahead reach tile 0.
        plan.wanted(centrePx = -900.0, widthPx = 1_080, direction = 1)
        assertThat(plan.visibleCount).isEqualTo(0)
        assertThat(plan.wanted()).isEqualTo(0..0)
    }

    @Test
    fun `a fractional centre takes the floor of the left edge`() {
        val plan = TilePlan(tileCount = 100)
        // Left edge 255.5 floors to 255 (tile 0); the last pixel 255 + 255 = 510 is in tile 1.
        plan.wanted(centrePx = 383.5, widthPx = 256, direction = 0)
        assertThat(plan.visible()).isEqualTo(0..1)
    }

    @Test
    fun `isWanted matches the wanted range`() {
        val plan = TilePlan(tileCount = 100)
        plan.wanted(centrePx = 1_000.0, widthPx = 1_080, direction = 1)
        assertThat(plan.isWanted(0)).isFalse()
        assertThat(plan.isWanted(1)).isTrue()
        assertThat(plan.isWanted(8)).isTrue()
        assertThat(plan.isWanted(9)).isFalse()
    }

    @Test
    fun `an empty view, an empty track or a NaN centre wants nothing`() {
        val plan = TilePlan(tileCount = 100)
        plan.wanted(centrePx = 1_000.0, widthPx = 0, direction = 1)
        assertThat(plan.visibleCount).isEqualTo(0)
        assertThat(plan.wantedCount).isEqualTo(0)
        plan.wanted(centrePx = Double.NaN, widthPx = 1_080, direction = 1)
        assertThat(plan.wantedCount).isEqualTo(0)
        val empty = TilePlan(tileCount = 0)
        empty.wanted(centrePx = 0.0, widthPx = 1_080, direction = 1)
        assertThat(empty.visibleCount).isEqualTo(0)
        assertThat(empty.wantedCount).isEqualTo(0)
    }

    @Test
    fun `the tile count covers the whole track at the scale`() {
        // 60 s at 0.135 px/ms (1080 px for 8 s) is 8100 px: 31.6 tiles, so 32.
        assertThat(TilePlan.tileCountFor(durationMs = 60_000.0, pxPerMs = 0.135)).isEqualTo(32)
        assertThat(TilePlan.tileCountFor(durationMs = 256.0, pxPerMs = 1.0)).isEqualTo(1)
        assertThat(TilePlan.tileCountFor(durationMs = 257.0, pxPerMs = 1.0)).isEqualTo(2)
        assertThat(TilePlan.tileCountFor(durationMs = 0.0, pxPerMs = 1.0)).isEqualTo(0)
        assertThat(TilePlan.tileCountFor(durationMs = Double.NaN, pxPerMs = 1.0)).isEqualTo(0)
    }

    @Test
    fun `the cache holds at least twelve tiles and always the visible ones plus the prefetch`() {
        assertThat(TilePlan.capacityFor(widthPx = 1_080)).isEqualTo(12)
        // A 2400 px landscape view touches up to 11 tiles; with 2 ahead and a spare on each side, 15.
        assertThat(TilePlan.capacityFor(widthPx = 2_400)).isEqualTo(15)
    }

    @Test
    fun `the wanted tiles always fit in the cache`() {
        for (width in listOf(1, 255, 256, 257, 1_080, 2_400, 3_200)) {
            val plan = TilePlan(tileCount = 1_000)
            for (direction in -1..1) {
                for (centre in 0 until 2_000 step 37) {
                    plan.wanted(centrePx = 10_000.0 + centre + 0.5, widthPx = width, direction = direction)
                    assertThat(plan.wantedCount).isAtMost(TilePlan.capacityFor(width))
                }
            }
        }
    }
}
