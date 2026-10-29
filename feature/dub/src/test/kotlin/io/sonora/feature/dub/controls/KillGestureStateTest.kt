package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KillGestureStateTest {

    @Test
    fun `a down kills at once`() {
        val kill = KillGestureState()
        assertThat(kill.down(1_000, killed = false)).isTrue()
    }

    @Test
    fun `a tap toggles the kill on and the next tap toggles it off`() {
        val kill = KillGestureState()
        assertThat(kill.down(1_000, killed = false)).isTrue()
        assertThat(kill.up(1_100)).isNull()
        assertThat(kill.down(2_000, killed = true)).isFalse()
        assertThat(kill.up(2_100)).isNull()
    }

    @Test
    fun `a hold is momentary and lifting restores the way`() {
        val kill = KillGestureState()
        assertThat(kill.down(1_000, killed = false)).isTrue()
        assertThat(kill.up(1_000 + KillGestureState.HOLD_MS)).isFalse()
    }

    @Test
    fun `holding a killed way brings it back only while held`() {
        val kill = KillGestureState()
        assertThat(kill.down(1_000, killed = true)).isFalse()
        assertThat(kill.up(1_800)).isTrue()
    }

    @Test
    fun `a cancel restores the state before the press`() {
        val kill = KillGestureState()
        kill.down(1_000, killed = false)
        assertThat(kill.cancel()).isFalse()
        kill.down(2_000, killed = true)
        assertThat(kill.cancel()).isTrue()
    }

    @Test
    fun `an up or a cancel with no press changes nothing`() {
        val kill = KillGestureState()
        assertThat(kill.up(1_000)).isNull()
        assertThat(kill.cancel()).isNull()
        kill.down(1_000, killed = false)
        kill.up(1_050)
        assertThat(kill.up(1_060)).isNull()
    }
}
