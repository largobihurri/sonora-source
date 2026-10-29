package io.sonora.player.wear.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteModeTest {

    @Test
    fun `the remote screens map to the modes that subscribe`() {
        assertThat(remoteModeFor(RemoteRoutes.NOW_PLAYING)).isEqualTo(RemoteMode.NowPlaying)
        assertThat(remoteModeFor(RemoteRoutes.QUEUE)).isEqualTo(RemoteMode.Queue)
    }

    @Test
    fun `any other screen leaves the remote off`() {
        assertThat(remoteModeFor("tracks")).isEqualTo(RemoteMode.Off)
        assertThat(remoteModeFor("now_playing")).isEqualTo(RemoteMode.Off)
        assertThat(remoteModeFor(null)).isEqualTo(RemoteMode.Off)
    }

    @Test
    fun `the remote is off whenever the app is not in the foreground`() {
        assertThat(effectiveMode(RemoteMode.Queue, foreground = false)).isEqualTo(RemoteMode.Off)
        assertThat(effectiveMode(RemoteMode.Queue, foreground = true)).isEqualTo(RemoteMode.Queue)
    }
}
