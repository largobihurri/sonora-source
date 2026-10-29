package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrustedNodesTest {
    private var now = 0L
    private var lookups = 0
    private var nodes: Set<String> = setOf("watch-1")
    private var failure: Exception? = null

    private val trusted = TrustedNodes(
        lookup = {
            lookups++
            failure?.let { throw it }
            nodes
        },
        now = { now },
        ttlMs = 60_000,
    )

    @Test
    fun `a node that declares the watch capability is trusted`() = runBlocking {
        assertThat(trusted.isTrusted("watch-1")).isTrue()
    }

    @Test
    fun `any other node is not`() = runBlocking {
        assertThat(trusted.isTrusted("stranger")).isFalse()
        assertThat(trusted.isTrusted("")).isFalse()
    }

    @Test
    fun `a trusted node is remembered for the time to live`() = runBlocking {
        trusted.isTrusted("watch-1")
        now = 59_999
        trusted.isTrusted("watch-1")

        assertThat(lookups).isEqualTo(1)

        now = 60_001
        trusted.isTrusted("watch-1")
        assertThat(lookups).isEqualTo(2)
    }

    @Test
    fun `a node that left the capability is dropped on the next lookup`() = runBlocking {
        assertThat(trusted.isTrusted("watch-1")).isTrue()
        nodes = emptySet()
        now = 61_000

        assertThat(trusted.isTrusted("watch-1")).isFalse()
    }

    @Test
    fun `an unknown node is looked up again each time, so a newly paired watch is found at once`() = runBlocking {
        assertThat(trusted.isTrusted("watch-2")).isFalse()
        nodes = setOf("watch-1", "watch-2")

        assertThat(trusted.isTrusted("watch-2")).isTrue()
    }

    @Test
    fun `a failed lookup trusts nobody, not even a remembered node after it expired`() = runBlocking {
        assertThat(trusted.isTrusted("watch-1")).isTrue()
        failure = IOException("Data Layer gone")
        now = 61_000

        assertThat(trusted.isTrusted("watch-1")).isFalse()
    }
}
