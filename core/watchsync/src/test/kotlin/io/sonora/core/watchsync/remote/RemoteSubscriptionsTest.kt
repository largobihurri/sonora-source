package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteSubscriptionsTest {
    private val subscriptions = RemoteSubscriptions(leaseMs = 60_000)
    private val playing = RemoteState(isPlaying = true, volume = 5, volumeMax = 15)
    private val withQueue = playing.copy(queue = listOf(RemoteQueueItem(1, "A", "B")))

    private fun due(now: Long, state: RemoteState = playing, full: RemoteState = withQueue) =
        subscriptions.due(now) { queue -> if (queue) full else state }

    @Test
    fun `nobody subscribed means nothing to push`() {
        assertThat(due(0)).isEmpty()
        assertThat(subscriptions.hasSubscribers(0)).isFalse()
    }

    @Test
    fun `a new subscriber gets the state at once, then only changes`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)

        assertThat(due(0)).containsExactly(RemotePush("watch", playing))
        assertThat(due(10)).isEmpty()
        assertThat(due(20, state = playing.copy(isPlaying = false))).hasSize(1)
    }

    @Test
    fun `a subscriber that wants the queue gets the state with it`() {
        subscriptions.subscribe("watch", withQueue = true, now = 0)

        assertThat(due(0).single().state.queue).isNotNull()
    }

    @Test
    fun `renewing the subscription pushes the state again, which resyncs the watch`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)
        due(0)
        subscriptions.subscribe("watch", withQueue = false, now = 20_000)

        assertThat(due(20_000)).hasSize(1)
    }

    @Test
    fun `switching to the queue view pushes the state with the queue`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)
        due(0)
        subscriptions.subscribe("watch", withQueue = true, now = 1_000)

        assertThat(due(1_000).single().state.queue).isNotNull()
    }

    @Test
    fun `an expired lease is dropped without a push`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)

        assertThat(due(60_001)).isEmpty()
        assertThat(subscriptions.hasSubscribers(60_001)).isFalse()
    }

    @Test
    fun `renewing keeps the lease alive past the first term`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)
        subscriptions.subscribe("watch", withQueue = false, now = 50_000)

        assertThat(subscriptions.hasSubscribers(100_000)).isTrue()
    }

    @Test
    fun `unsubscribing stops the pushes`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)
        subscriptions.unsubscribe("watch")

        assertThat(due(0, state = playing.copy(isPlaying = false))).isEmpty()
    }

    @Test
    fun `a push that could not be sent is owed again at the next change`() {
        subscriptions.subscribe("watch", withQueue = false, now = 0)
        due(0)
        subscriptions.markUnsent("watch")

        assertThat(due(1)).hasSize(1)
        assertThat(due(2)).isEmpty()
    }

    @Test
    fun `each watch has its own lease`() {
        subscriptions.subscribe("a", withQueue = false, now = 0)
        subscriptions.subscribe("b", withQueue = true, now = 0)

        assertThat(due(0).map { it.nodeId }).containsExactly("a", "b")
        subscriptions.unsubscribe("a")
        assertThat(subscriptions.hasSubscribers(1)).isTrue()
    }
}
