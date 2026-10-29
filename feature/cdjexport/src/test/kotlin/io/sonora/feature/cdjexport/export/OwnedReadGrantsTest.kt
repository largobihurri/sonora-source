package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnedReadGrantsTest {

    private val system = FakeSystemGrants()
    private val store = MemoryGrantStore()

    private fun grants(maxOwned: Int = OwnedReadGrants.MAX_OWNED) = OwnedReadGrants(store, system, maxOwned)

    @Test
    fun `a grant the export took is given up again`() {
        val grants = grants()

        assertThat(grants.take("content://a")).isTrue()
        assertThat(system.grants).containsExactly("content://a")
        assertThat(store.saved).containsExactly("content://a")

        grants.release("content://a")

        assertThat(system.grants).isEmpty()
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a grant the app held before for something else is never given up`() {
        system.grants += "content://tree/music"
        val grants = grants()

        assertThat(grants.take("content://tree/music")).isTrue()
        grants.release("content://tree/music")
        grants.releaseLeftovers()

        assertThat(system.grants).containsExactly("content://tree/music")
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `taking the same grant twice keeps it the export's`() {
        val grants = grants()

        grants.take("content://a")
        grants.take("content://a")
        grants.release("content://a")

        assertThat(system.grants).isEmpty()
    }

    @Test
    fun `grants a killed run kept are given up by the next run unless it takes them again`() {
        grants().apply {
            take("content://a")
            take("content://b")
        }

        val nextRun = grants()
        nextRun.take("content://b")
        nextRun.releaseLeftovers()

        assertThat(system.grants).containsExactly("content://b")
        assertThat(store.saved).containsExactly("content://b")
        nextRun.releaseLeftovers()
        assertThat(system.grants).containsExactly("content://b")
    }

    @Test
    fun `no more than the limit is taken, so the app's other grants are never pushed out`() {
        val grants = grants(maxOwned = 2)

        assertThat(grants.take("content://a")).isTrue()
        assertThat(grants.take("content://b")).isTrue()
        assertThat(grants.take("content://c")).isFalse()

        assertThat(system.grants).containsExactly("content://a", "content://b")
        grants.release("content://a")
        assertThat(grants.take("content://c")).isTrue()
    }

    @Test
    fun `a provider without persistable access is not recorded`() {
        system.refused += "content://cloud/x"

        assertThat(grants().take("content://cloud/x")).isFalse()
        assertThat(store.saved).isEmpty()
    }

    private class FakeSystemGrants : SystemReadGrants {
        val grants = mutableSetOf<String>()
        val refused = mutableSetOf<String>()

        override fun held(): Set<String> = grants.toSet()

        override fun take(uri: String): Boolean = uri !in refused && grants.add(uri).let { true }

        override fun release(uri: String) {
            grants -= uri
        }
    }

    private class MemoryGrantStore : GrantStore {
        var saved: Set<String> = emptySet()

        override fun load(): Set<String> = saved

        override fun save(uris: Set<String>) {
            saved = uris.toSet()
        }
    }
}
