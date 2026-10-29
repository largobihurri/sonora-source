package io.sonora.feature.library.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchCapsuleLayoutTest {

    @Test
    fun `without the keyboard the capsule floats right above the bottom chrome`() {
        assertThat(searchCapsuleBottom(chromePadding = 170f, ime = 0f)).isEqualTo(170f)
    }

    @Test
    fun `a minimised chrome lets the capsule sit lower`() {
        assertThat(searchCapsuleBottom(chromePadding = 100f, ime = 0f)).isLessThan(searchCapsuleBottom(chromePadding = 170f, ime = 0f))
    }

    @Test
    fun `the keyboard lifts the capsule to just above its top edge`() {
        assertThat(searchCapsuleBottom(chromePadding = 170f, ime = 320f)).isEqualTo(320f + SEARCH_CAPSULE_KEYBOARD_GAP)
    }

    @Test
    fun `a keyboard still below the chrome while it opens or closes leaves the capsule above the chrome`() {
        assertThat(searchCapsuleBottom(chromePadding = 170f, ime = 60f)).isEqualTo(170f)
    }

    @Test
    fun `results end clear of the capsule and follow it up with the keyboard`() {
        assertThat(searchContentPadding(chromePadding = 170f, ime = 0f))
            .isEqualTo(170f + SEARCH_CAPSULE_HEIGHT + SEARCH_CAPSULE_CONTENT_GAP)
        assertThat(searchContentPadding(chromePadding = 170f, ime = 320f))
            .isEqualTo(320f + SEARCH_CAPSULE_KEYBOARD_GAP + SEARCH_CAPSULE_HEIGHT + SEARCH_CAPSULE_CONTENT_GAP)
    }

    @Test
    fun `the capsule is at least a touch target tall`() {
        assertThat(SEARCH_CAPSULE_HEIGHT).isAtLeast(44f)
    }
}
