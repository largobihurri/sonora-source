package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagEditCancelTest {

    @Test
    fun `cancelling an untouched form closes it`() {
        assertThat(tagEditCancel(alertOpen = false, dirty = false)).isEqualTo(TagEditCancel.Close)
    }

    @Test
    fun `cancelling a changed form asks before discarding`() {
        assertThat(tagEditCancel(alertOpen = false, dirty = true)).isEqualTo(TagEditCancel.ConfirmDiscard)
    }

    @Test
    fun `cancelling while an alert is open closes only the alert`() {
        assertThat(tagEditCancel(alertOpen = true, dirty = false)).isEqualTo(TagEditCancel.CloseAlert)
        assertThat(tagEditCancel(alertOpen = true, dirty = true)).isEqualTo(TagEditCancel.CloseAlert)
    }
}
