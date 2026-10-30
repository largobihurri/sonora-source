package io.sonora.core.dubmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnavailableDubModeTest {

    @Test
    fun `a build without DUB mode does not offer it and keeps it off when asked to turn it on`() {
        assertThat(UnavailableDubMode.offered).isFalse()
        assertThat(UnavailableDubMode.enabled.value).isFalse()

        UnavailableDubMode.setEnabled(true)

        assertThat(UnavailableDubMode.enabled.value).isFalse()
    }
}
