package io.sonora.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepeatModeTest {

    @Test
    fun `repeat cycles off, all, one and back to off`() {
        assertThat(RepeatMode.OFF.next()).isEqualTo(RepeatMode.ALL)
        assertThat(RepeatMode.ALL.next()).isEqualTo(RepeatMode.ONE)
        assertThat(RepeatMode.ONE.next()).isEqualTo(RepeatMode.OFF)
    }
}
