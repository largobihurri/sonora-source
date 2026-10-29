package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Tap button counts a touch when the finger lands, and its click (which comes at the lift, after the touch watcher
 * has already seen the lift on the earlier pass) must not count the same touch a second time.
 */
class PressClickGateTest {

    @Test
    fun `a touch counts once, at the press, even though the lift is seen before the click`() {
        val gate = PressClickGate()
        assertThat(gate.pressed()).isTrue()
        gate.ended(released = true)
        assertThat(gate.clicked()).isFalse()
    }

    @Test
    fun `every touch in a row counts exactly once`() {
        val gate = PressClickGate()
        val counted = (1..8).count {
            val atPress = gate.pressed()
            gate.ended(released = true)
            atPress && !gate.clicked()
        }
        assertThat(counted).isEqualTo(8)
    }

    @Test
    fun `a click with no touch before it counts (TalkBack or a keyboard)`() {
        val gate = PressClickGate()
        assertThat(gate.clicked()).isTrue()
        assertThat(gate.clicked()).isTrue()
    }

    @Test
    fun `a cancelled touch leaves no mark, so the next accessibility click still counts`() {
        val gate = PressClickGate()
        gate.pressed()
        gate.ended(released = false)
        assertThat(gate.clicked()).isTrue()
    }

    @Test
    fun `an accessibility click after a completed touch counts`() {
        val gate = PressClickGate()
        gate.pressed()
        gate.ended(released = true)
        gate.clicked()
        assertThat(gate.clicked()).isTrue()
    }
}
