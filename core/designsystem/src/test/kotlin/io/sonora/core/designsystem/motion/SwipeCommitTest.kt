package io.sonora.core.designsystem.motion

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A committed mini player swipe: slide out, change track, wait for the new entry (bounded), slide in. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeCommitTest {

    private val events = mutableListOf<String>()
    private val itemKey = MutableStateFlow<Long?>(1L)

    private fun TestScope.motion(failOut: Boolean = false) = object : SwipeMotion {
        override suspend fun slideOut() {
            events += "out@$currentTime"
            check(!failOut) { "animation failed" }
        }
        override suspend fun slideIn() { events += "in@$currentTime" }
        override suspend fun reset() { events += "reset@$currentTime" }
    }

    private fun TestScope.skip(to: Long?) = { events += "skip@$currentTime"; if (to != null) itemKey.value = to }

    @Test
    fun `the new track slides in as soon as the player reports it`() = runTest {
        val swipe = SwipeCommit(backgroundScope)
        swipe.commit(change = { events += "skip@$currentTime" }, itemKeys = itemKey, motion = motion(), reducedMotion = false)
        runCurrent()
        assertThat(swipe.running).isTrue()

        advanceTimeBy(100)
        itemKey.value = 2L
        runCurrent()

        assertThat(events).containsExactly("out@0", "skip@0", "in@100").inOrder()
        assertThat(swipe.running).isFalse()
    }

    @Test
    fun `when the entry never changes (previous restarts the same track) it slides in after the wait`() = runTest {
        val swipe = SwipeCommit(backgroundScope)
        swipe.commit(change = skip(to = null), itemKeys = itemKey, motion = motion(), reducedMotion = false)

        advanceTimeBy(SwipeCommit.NEW_ITEM_WAIT_MILLIS - 1)
        runCurrent()
        assertThat(events).containsExactly("out@0", "skip@0").inOrder()
        assertThat(swipe.running).isTrue()
        advanceTimeBy(1)
        runCurrent()

        assertThat(events.last()).isEqualTo("in@${SwipeCommit.NEW_ITEM_WAIT_MILLIS}")
        assertThat(swipe.running).isFalse()
    }

    @Test
    fun `a swipe committed while one is running is ignored`() = runTest {
        val swipe = SwipeCommit(backgroundScope)
        val first = swipe.commit(change = skip(to = null), itemKeys = itemKey, motion = motion(), reducedMotion = false)
        runCurrent()

        val second = swipe.commit(change = { events += "second" }, itemKeys = itemKey, motion = motion(), reducedMotion = false)
        advanceTimeBy(SwipeCommit.NEW_ITEM_WAIT_MILLIS + 1) // backgroundScope work is not awaited by advanceUntilIdle
        runCurrent()

        assertThat(first).isNotNull()
        assertThat(second).isNull()
        assertThat(events).doesNotContain("second")
        assertThat(swipe.commit(change = skip(to = 3L), itemKeys = itemKey, motion = motion(), reducedMotion = false)).isNotNull()
    }

    @Test
    fun `the running flag is cleared when the swipe is cancelled or its animation fails`() = runTest {
        val swipe = SwipeCommit(backgroundScope)
        val job = swipe.commit(change = skip(to = null), itemKeys = itemKey, motion = motion(), reducedMotion = false)!!
        runCurrent()
        job.cancel()
        runCurrent()
        assertThat(swipe.running).isFalse()

        val failures = mutableListOf<Throwable>()
        val failing = SwipeCommit(CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e -> failures += e }))
        failing.commit(change = skip(to = 2L), itemKeys = itemKey, motion = motion(failOut = true), reducedMotion = false)
        advanceUntilIdle()
        assertThat(failures.single()).hasMessageThat().isEqualTo("animation failed")
        assertThat(failing.running).isFalse()
    }

    @Test
    fun `with reduced motion the track changes and the content resets without sliding or waiting`() = runTest {
        val swipe = SwipeCommit(backgroundScope)

        swipe.commit(change = skip(to = null), itemKeys = itemKey, motion = motion(), reducedMotion = true)
        runCurrent()

        assertThat(events).containsExactly("skip@0", "reset@0").inOrder()
        assertThat(swipe.running).isFalse()
    }
}
