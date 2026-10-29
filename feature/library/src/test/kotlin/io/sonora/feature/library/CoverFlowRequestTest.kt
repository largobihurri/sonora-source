package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverFlowRequestTest {

    @Test
    fun `a phone shows the Cover Flow whenever it is held sideways, asked for or not`() {
        for (requested in listOf(true, false)) {
            assertThat(coverFlowOnScreen(large = false, landscape = true, requested = requested)).isTrue()
            assertThat(coverFlowOnScreen(large = false, landscape = false, requested = requested)).isFalse()
        }
    }

    @Test
    fun `a large screen shows the Cover Flow only when asked for, whichever way it is held`() {
        for (landscape in listOf(true, false)) {
            assertThat(coverFlowOnScreen(large = true, landscape = landscape, requested = false)).isFalse()
            assertThat(coverFlowOnScreen(large = true, landscape = landscape, requested = true)).isTrue()
        }
    }

    @Test
    fun `a large screen hides the list under its Cover Flow from accessibility, a phone keeps today's tree`() {
        assertThat(listHiddenBehindCoverFlow(large = true, coverFlowShown = true)).isTrue()
        assertThat(listHiddenBehindCoverFlow(large = true, coverFlowShown = false)).isFalse()
        assertThat(listHiddenBehindCoverFlow(large = false, coverFlowShown = true)).isFalse()
        assertThat(listHiddenBehindCoverFlow(large = false, coverFlowShown = false)).isFalse()
    }
}
