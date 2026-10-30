package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisualCollectionRequestTest {

    @Test
    fun `a phone shows the visual collection whenever it is held sideways, asked for or not`() {
        for (requested in listOf(true, false)) {
            assertThat(visualCollectionOnScreen(large = false, landscape = true, requested = requested)).isTrue()
            assertThat(visualCollectionOnScreen(large = false, landscape = false, requested = requested)).isFalse()
        }
    }

    @Test
    fun `a large screen shows the visual collection only when asked for, whichever way it is held`() {
        for (landscape in listOf(true, false)) {
            assertThat(visualCollectionOnScreen(large = true, landscape = landscape, requested = false)).isFalse()
            assertThat(visualCollectionOnScreen(large = true, landscape = landscape, requested = true)).isTrue()
        }
    }

    @Test
    fun `a large screen hides the list under its visual collection from accessibility, a phone keeps today's tree`() {
        assertThat(listHiddenBehindVisualCollection(large = true, visualCollectionShown = true)).isTrue()
        assertThat(listHiddenBehindVisualCollection(large = true, visualCollectionShown = false)).isFalse()
        assertThat(listHiddenBehindVisualCollection(large = false, visualCollectionShown = true)).isFalse()
        assertThat(listHiddenBehindVisualCollection(large = false, visualCollectionShown = false)).isFalse()
    }
}
