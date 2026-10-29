package io.sonora.core.watchport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.WatchNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UnavailableWatchSyncTest {

    @Test
    fun `a build without the watch link offers no watch and starts no send`() {
        assertThat(UnavailableWatchSync.isAvailable).isFalse()
        assertThat(UnavailableWatchSync.sendAlbum(1)).isFalse()
        assertThat(UnavailableWatchSync.sendPlaylist(1)).isFalse()
        assertThat(UnavailableWatchSync.sendTracks(emptyList())).isFalse()
        assertThat(UnavailableWatchSync.state.value).isEqualTo(WatchSyncState.Idle)
    }

    @Test
    fun `reading or clearing the watch finds no watch`() = runBlocking {
        assertThat(UnavailableWatchSync.readWatch().exceptionOrNull()).isInstanceOf(WatchNotFoundException::class.java)
        assertThat(UnavailableWatchSync.removeFromWatch(listOf("a")).exceptionOrNull())
            .isInstanceOf(WatchNotFoundException::class.java)
    }

    @Test
    fun `a real controller is available unless it says otherwise`() {
        val controller = object : WatchSyncController by UnavailableWatchSync {
            override val isAvailable: Boolean get() = super<WatchSyncController>.isAvailable
        }
        assertThat(controller.isAvailable).isTrue()
    }
}
