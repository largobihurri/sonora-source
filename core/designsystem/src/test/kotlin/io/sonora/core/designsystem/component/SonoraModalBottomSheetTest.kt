package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SonoraModalBottomSheetTest {

    @Test
    fun `a sheet may grow up to the status bar and no further`() {
        // The window is 2400 px tall and the status bar 112 px: the sheet's top stays at or below y = 112.
        assertThat(sheetMaxHeight(containerHeight = 2400, topInset = 112)).isEqualTo(2288)
    }

    @Test
    fun `no inset leaves the whole window`() {
        assertThat(sheetMaxHeight(containerHeight = 2400, topInset = 0)).isEqualTo(2400)
    }

    @Test
    fun `an inset taller than the window leaves no height instead of a negative one`() {
        assertThat(sheetMaxHeight(containerHeight = 100, topInset = 112)).isEqualTo(0)
    }

    // Material 3 computes its anchors inside the capped box (Expanded = cap - height, Hidden = cap) and offsets the
    // sheet from where the box is placed, so a sheet sits at placement.y + anchor.

    @Test
    fun `a sheet that fills the cap ends exactly at the container's bottom`() {
        val cap = sheetMaxHeight(containerHeight = 2400, topInset = 112)
        val placement = sheetPlacement(containerHeight = 2400, topInset = 112, measuredHeight = cap)
        val expandedAnchor = cap - cap
        assertThat(placement.y + expandedAnchor + cap).isEqualTo(2400)
        // ... and its top stays on the status bar's line, not above it.
        assertThat(placement.y + expandedAnchor).isEqualTo(112)
    }

    @Test
    fun `a short sheet's expanded top is the container's height minus its own`() {
        val placement = sheetPlacement(containerHeight = 2400, topInset = 112, measuredHeight = 900)
        val cap = sheetMaxHeight(containerHeight = 2400, topInset = 112)
        val expandedAnchor = cap - 900
        assertThat(placement.y + expandedAnchor).isEqualTo(2400 - 900)
    }

    @Test
    fun `a hidden sheet is fully off the container`() {
        val placement = sheetPlacement(containerHeight = 2400, topInset = 112, measuredHeight = 900)
        val cap = sheetMaxHeight(containerHeight = 2400, topInset = 112)
        val hiddenAnchor = cap
        assertThat(placement.y + hiddenAnchor).isEqualTo(2400)
    }

    @Test
    fun `the placed box takes the inset plus the sheet and never more than the container`() {
        val cap = sheetMaxHeight(containerHeight = 2400, topInset = 112)
        val placement = sheetPlacement(containerHeight = 2400, topInset = 112, measuredHeight = cap)
        assertThat(placement.reportedHeight).isEqualTo(2400)
        assertThat(sheetPlacement(2400, 112, 900).reportedHeight).isEqualTo(1012)
    }

    @Test
    fun `no inset places the sheet exactly as before`() {
        assertThat(sheetPlacement(containerHeight = 2400, topInset = 0, measuredHeight = 900))
            .isEqualTo(SheetPlacement(reportedHeight = 900, y = 0))
    }

    @Test
    fun `an inset taller than the container is clamped to it`() {
        val placement = sheetPlacement(containerHeight = 100, topInset = 112, measuredHeight = 0)
        assertThat(placement.y).isEqualTo(100)
        assertThat(placement.reportedHeight).isEqualTo(100)
    }
}
