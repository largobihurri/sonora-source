package io.sonora.core.watchport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.NotEnoughSpaceException
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.WatchNotFoundException
import java.io.EOFException
import java.io.IOException
import org.junit.Test

class WatchSyncFailureTest {

    @Test
    fun `no watch is no watch`() {
        assertThat(WatchSyncFailure.of(WatchNotFoundException())).isEqualTo(WatchSyncFailure.NoWatch)
    }

    @Test
    fun `a protocol error means the watch app is another version`() {
        assertThat(WatchSyncFailure.of(ProtocolException("version"))).isEqualTo(WatchSyncFailure.WatchAppIncompatible)
    }

    @Test
    fun `a full watch carries the numbers`() {
        assertThat(WatchSyncFailure.of(NotEnoughSpaceException(10, 4))).isEqualTo(WatchSyncFailure.NotEnoughSpace(10, 4))
    }

    @Test
    fun `any io error is a lost connection`() {
        assertThat(WatchSyncFailure.of(IOException("x"))).isEqualTo(WatchSyncFailure.ConnectionLost)
        assertThat(WatchSyncFailure.of(EOFException())).isEqualTo(WatchSyncFailure.ConnectionLost)
    }

    @Test
    fun `anything else is unexpected and keeps its message`() {
        assertThat(WatchSyncFailure.of(IllegalStateException("boom"))).isEqualTo(WatchSyncFailure.Unexpected("boom"))
    }
}
