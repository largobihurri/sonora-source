package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueMovesTest {

    @Test
    fun `an upcoming song moves one place either way`() {
        assertThat(moveTarget(QueueDirection.Up, index = 3, currentIndex = 1, count = 5)).isEqualTo(2)
        assertThat(moveTarget(QueueDirection.Down, index = 3, currentIndex = 1, count = 5)).isEqualTo(4)
    }

    @Test
    fun `nothing moves to or past the playing song`() {
        assertThat(moveTarget(QueueDirection.Up, index = 2, currentIndex = 1, count = 5)).isNull()
    }

    @Test
    fun `nothing moves off the end`() {
        assertThat(moveTarget(QueueDirection.Down, index = 4, currentIndex = 1, count = 5)).isNull()
    }

    @Test
    fun `the playing song, played songs and indices outside the queue do not move`() {
        assertThat(moveTarget(QueueDirection.Down, index = 1, currentIndex = 1, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Down, index = 0, currentIndex = 1, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Up, index = 9, currentIndex = 1, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Up, index = -1, currentIndex = 1, count = 5)).isNull()
    }
}
