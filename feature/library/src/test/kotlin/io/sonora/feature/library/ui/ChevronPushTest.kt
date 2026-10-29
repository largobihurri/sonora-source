package io.sonora.feature.library.ui

import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import io.sonora.feature.library.ui.ChevronPush.Claim
import org.junit.Test

class ChevronPushTest {

    private val width = 1080f
    private val fling = 1000f

    @Test
    fun `a drag may start only in the trailing strip`() {
        assertThat(ChevronPush.canStart(downX = 1000f, width = width, strip = 150f)).isTrue()
        assertThat(ChevronPush.canStart(downX = 900f, width = width, strip = 150f)).isFalse()
        assertThat(ChevronPush.canStart(downX = 930f, width = width, strip = 150f)).isTrue()
        assertThat(ChevronPush.canStart(downX = 1085f, width = width, strip = 150f)).isFalse()
        assertThat(ChevronPush.canStart(downX = 10f, width = 0f, strip = 150f)).isFalse()
    }

    @Test
    fun `the page starts with its edge under the finger`() {
        assertThat(ChevronPush.startOffset(downX = 1040f, width = width)).isEqualTo(40f)
        assertThat(ChevronPush.startOffset(downX = 1100f, width = width)).isEqualTo(0f)
        assertThat(ChevronPush.startOffset(downX = -5f, width = width)).isEqualTo(1080f)
    }

    @Test
    fun `the drag is undecided inside the slop`() {
        assertThat(ChevronPush.claim(travelX = -5f, travelY = 3f, slop = 16f)).isEqualTo(Claim.Undecided)
        assertThat(ChevronPush.claim(travelX = 0f, travelY = 0f, slop = 16f)).isEqualTo(Claim.Undecided)
    }

    @Test
    fun `a leftward drag claims the push, vertical and rightward drags yield to the list`() {
        assertThat(ChevronPush.claim(travelX = -20f, travelY = 4f, slop = 16f)).isEqualTo(Claim.Push)
        assertThat(ChevronPush.claim(travelX = -10f, travelY = -20f, slop = 16f)).isEqualTo(Claim.Yield)
        assertThat(ChevronPush.claim(travelX = -16f, travelY = -16f, slop = 16f)).isEqualTo(Claim.Yield) // a tie goes to the list
        assertThat(ChevronPush.claim(travelX = 20f, travelY = 0f, slop = 16f)).isEqualTo(Claim.Yield)
    }

    @Test
    fun `the page follows the finger but never back past its rest`() {
        assertThat(ChevronPush.dragged(offset = 100f, dx = -30f)).isEqualTo(130f)
        assertThat(ChevronPush.dragged(offset = 100f, dx = 150f)).isEqualTo(0f)
    }

    @Test
    fun `progress is the share of the width dragged in`() {
        assertThat(ChevronPush.progress(offset = 270f, width = width)).isEqualTo(0.25f)
        assertThat(ChevronPush.progress(offset = 2000f, width = width)).isEqualTo(1f)
        assertThat(ChevronPush.progress(offset = 10f, width = 0f)).isEqualTo(0f)
    }

    @Test
    fun `released past half the width it opens, before that it slides back`() {
        assertThat(ChevronPush.shouldPush(offset = 540f, velocityX = 0f, width = width, flingVelocity = fling, cancelled = false)).isTrue()
        assertThat(ChevronPush.shouldPush(offset = 539f, velocityX = 0f, width = width, flingVelocity = fling, cancelled = false)).isFalse()
        assertThat(ChevronPush.shouldPush(offset = 0f, velocityX = 0f, width = width, flingVelocity = fling, cancelled = false)).isFalse()
    }

    @Test
    fun `a leftward fling opens a short drag, a rightward one cancels a long one`() {
        assertThat(ChevronPush.shouldPush(offset = 60f, velocityX = -1500f, width = width, flingVelocity = fling, cancelled = false)).isTrue()
        assertThat(ChevronPush.shouldPush(offset = 900f, velocityX = 1500f, width = width, flingVelocity = fling, cancelled = false)).isFalse()
        assertThat(ChevronPush.shouldPush(offset = 0f, velocityX = -1500f, width = width, flingVelocity = fling, cancelled = false)).isFalse()
    }

    @Test
    fun `a pointer the system takes back never opens the page`() {
        assertThat(ChevronPush.shouldPush(offset = 900f, velocityX = -1500f, width = width, flingVelocity = fling, cancelled = true)).isFalse()
    }

    @Test
    fun `in a right-to-left layout no drag starts, the stack still pushes from the right`() {
        assertThat(ChevronPush.canStart(downX = 1000f, width = width, strip = 150f, rtl = true)).isFalse()
        assertThat(ChevronPush.canStart(downX = 50f, width = width, strip = 150f, rtl = true)).isFalse()
    }

    @Test
    fun `the back exclusion is the row's share of the system zone, centred on the chevron`() {
        assertThat(ChevronPush.backExclusion(rowWidth = width, rowHeight = 150f, zoneWidth = 96f, maxHeight = 84f, rtl = false))
            .isEqualTo(Rect(984f, 33f, 1080f, 117f))
        // A row shorter than its share is excluded whole, a zone wider than the row is clipped to it.
        assertThat(ChevronPush.backExclusion(rowWidth = width, rowHeight = 60f, zoneWidth = 96f, maxHeight = 84f, rtl = false))
            .isEqualTo(Rect(984f, 0f, 1080f, 60f))
        assertThat(ChevronPush.backExclusion(rowWidth = 80f, rowHeight = 60f, zoneWidth = 96f, maxHeight = 84f, rtl = false))
            .isEqualTo(Rect(0f, 0f, 80f, 60f))
    }

    @Test
    fun `nothing is excluded without a system zone at the row or in a right-to-left layout`() {
        assertThat(ChevronPush.backExclusion(rowWidth = width, rowHeight = 150f, zoneWidth = 0f, maxHeight = 84f, rtl = false)).isNull()
        assertThat(ChevronPush.backExclusion(rowWidth = width, rowHeight = 150f, zoneWidth = -20f, maxHeight = 84f, rtl = false)).isNull()
        assertThat(ChevronPush.backExclusion(rowWidth = 0f, rowHeight = 150f, zoneWidth = 96f, maxHeight = 84f, rtl = false)).isNull()
        assertThat(ChevronPush.backExclusion(rowWidth = width, rowHeight = 150f, zoneWidth = 96f, maxHeight = 84f, rtl = true)).isNull()
    }
}
