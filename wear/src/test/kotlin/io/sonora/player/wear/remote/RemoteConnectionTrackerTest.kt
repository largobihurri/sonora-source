package io.sonora.player.wear.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteState
import org.junit.Test

class RemoteConnectionTrackerTest {
    private val tracker = RemoteConnectionTracker(staleMs = RemoteLimits.STALE_MS)
    private val state = RemoteState(volume = 5, volumeMax = 15)

    @Test
    fun `before a phone is found there is no phone`() {
        tracker.start(now = 0)

        assertThat(tracker.ui(now = 0)).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `a phone found but silent is being connected to, for a while`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)

        assertThat(tracker.ui(now = 1_000)).isEqualTo(RemoteUiState.Connecting)
        assertThat(tracker.ui(now = RemoteLimits.STALE_MS)).isEqualTo(RemoteUiState.Connecting)
        assertThat(tracker.ui(now = RemoteLimits.STALE_MS + 1)).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `a state from the phone is shown with the time it arrived`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)
        tracker.onState(state, now = 2_000)

        assertThat(tracker.ui(now = 3_000)).isEqualTo(RemoteUiState.Ready(state, receivedAt = 2_000))
    }

    @Test
    fun `a state that stops being renewed goes stale and the phone counts as gone`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)
        tracker.onState(state, now = 2_000)

        assertThat(tracker.ui(now = 2_000 + RemoteLimits.STALE_MS)).isInstanceOf(RemoteUiState.Ready::class.java)
        assertThat(tracker.ui(now = 2_001 + RemoteLimits.STALE_MS)).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `losing the phone from the Data Layer drops the state at once`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)
        tracker.onState(state, now = 1_000)
        tracker.onPhone(found = false, now = 1_200)

        assertThat(tracker.ui(now = 1_500)).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `a phone that comes back is connected to afresh, not shown with an old state`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)
        tracker.onState(state, now = 1_000)
        tracker.onPhone(found = false, now = 1_100)
        tracker.onPhone(found = true, now = 1_200)

        assertThat(tracker.ui(now = 1_500)).isEqualTo(RemoteUiState.Connecting)
    }

    @Test
    fun `a state is ignored while no phone is known`() {
        tracker.start(now = 0)
        tracker.onState(state, now = 100)

        assertThat(tracker.ui(now = 200)).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `starting again forgets the last session`() {
        tracker.start(now = 0)
        tracker.onPhone(found = true, now = 0)
        tracker.onState(state, now = 1_000)
        tracker.start(now = 5_000)

        assertThat(tracker.ui(now = 5_000)).isEqualTo(RemoteUiState.NoPhone)
    }
}
