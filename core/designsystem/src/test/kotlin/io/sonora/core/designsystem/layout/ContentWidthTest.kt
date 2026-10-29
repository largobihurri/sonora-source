package io.sonora.core.designsystem.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentWidthTest {

    private val pad = WindowClass.of(1152, 720)

    @Test
    fun `a phone has no cap and no inset`() {
        assertThat(ContentWidth.widthDp(WindowClass.Phone, availableDp = 320f, maxDp = 720)).isNull()
        assertThat(ContentWidth.insetDp(WindowClass.Phone, availableDp = 704f, maxDp = 720)).isEqualTo(0f)
        // A phone held sideways is wide but still not large.
        assertThat(ContentWidth.widthDp(WindowClass.of(704, 320), availableDp = 704f, maxDp = 560)).isNull()
    }

    @Test
    fun `a large window caps the content at the maximum and centres it`() {
        assertThat(ContentWidth.widthDp(pad, availableDp = 1152f, maxDp = 720)).isEqualTo(720f)
        assertThat(ContentWidth.insetDp(pad, availableDp = 1152f, maxDp = 720)).isEqualTo(216f)
    }

    @Test
    fun `content narrower than the maximum takes all the room`() {
        assertThat(ContentWidth.widthDp(pad, availableDp = 600f, maxDp = 720)).isEqualTo(600f)
        assertThat(ContentWidth.insetDp(pad, availableDp = 600f, maxDp = 720)).isEqualTo(0f)
    }

    @Test
    fun `width and insets always add up to the available width`() {
        for (available in listOf(480f, 720f, 721f, 832f, 1152f, 1280f)) {
            val width = ContentWidth.widthDp(pad, available, ContentWidth.LIST_DP)!!
            val inset = ContentWidth.insetDp(pad, available, ContentWidth.LIST_DP)
            assertThat(width + 2 * inset).isWithin(0.001f).of(available)
        }
    }

    @Test
    fun `lists are narrower than song tables, and both within the 720 to 840 dp range`() {
        assertThat(ContentWidth.LIST_DP).isEqualTo(720)
        assertThat(ContentWidth.TABLE_DP).isEqualTo(840)
        assertThat(ContentWidth.LIST_DP).isLessThan(ContentWidth.TABLE_DP)
    }
}
