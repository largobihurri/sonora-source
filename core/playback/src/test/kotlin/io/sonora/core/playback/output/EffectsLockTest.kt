package io.sonora.core.playback.output

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EffectsLockTest {

    @Test
    fun `while armed the value is the neutral one, whatever is stored`() = runTest {
        val shown = flowOf(5, 7).lockedTo(neutral = 0, armed = flowOf(true)).toList()

        assertThat(shown.distinct()).containsExactly(0)
    }

    @Test
    fun `while not armed the stored value passes through`() = runTest {
        val shown = flowOf(5, 7).lockedTo(neutral = 0, armed = flowOf(false)).toList()

        assertThat(shown).containsExactly(5, 7).inOrder()
    }

    @Test
    fun `the stored value is back as soon as it is disarmed`() = runTest {
        val armed = MutableStateFlow(true)
        val stored = MutableStateFlow(9)
        val seen = mutableListOf<Int>()

        val flow = stored.lockedTo(neutral = 0, armed = armed)
        flow.take(1).collect { seen += it }
        armed.value = false
        flow.take(1).collect { seen += it }

        assertThat(seen).containsExactly(0, 9).inOrder()
    }
}
