package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollToTopTest {

    @Test
    fun `a list ignores the requests made before it appeared, so nothing scrolls on first composition`() {
        val requests = ScrollToTop()
        requests.request()
        requests.request()

        val cursor = ScrollToTopCursor(requests.count)

        assertThat(cursor.take(requests.count)).isFalse()
        assertThat(cursor.take(requests.count)).isFalse() // recomposing reads the same count again
    }

    @Test
    fun `each new request scrolls once`() {
        val requests = ScrollToTop()
        val cursor = ScrollToTopCursor(requests.count)

        requests.request()

        assertThat(cursor.take(requests.count)).isTrue()
        assertThat(cursor.take(requests.count)).isFalse()

        requests.request()

        assertThat(cursor.take(requests.count)).isTrue()
    }

    @Test
    fun `requests made while the list was busy scroll it once`() {
        val requests = ScrollToTop()
        val cursor = ScrollToTopCursor(requests.count)

        requests.request()
        requests.request()
        requests.request()

        assertThat(cursor.take(requests.count)).isTrue()
        assertThat(cursor.take(requests.count)).isFalse()
    }

    @Test
    fun `each tab has its own requests`() {
        val library = ScrollToTop()
        val search = ScrollToTop()
        val searchCursor = ScrollToTopCursor(search.count)

        library.request()

        assertThat(searchCursor.take(search.count)).isFalse()
    }
}
