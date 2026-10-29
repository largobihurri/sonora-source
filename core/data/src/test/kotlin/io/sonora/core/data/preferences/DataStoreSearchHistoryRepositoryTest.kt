package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The recent searches against a real preferences DataStore file of their own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreSearchHistoryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_search_history.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreSearchHistoryRepository) -> T): T =
        withStore { block(DataStoreSearchHistoryRepository(it)) }

    @Test
    fun `nothing stored reads as no recent searches`() {
        assertThat(withRepository { it.recent.first() }).isEmpty()
    }

    @Test
    fun `recorded searches survive reopening the store, most recent first`() {
        withRepository {
            it.record("abba")
            it.record("queen")
            it.record("ABBA")
        }

        assertThat(withRepository { it.recent.first() }).containsExactly("ABBA", "queen").inOrder()
    }

    @Test
    fun `remove forgets one search and clear forgets them all`() {
        val afterRemove = withRepository {
            it.record("abba")
            it.record("queen")
            it.remove("Abba")
            it.recent.first()
        }
        assertThat(afterRemove).containsExactly("queen")

        val afterClear = withRepository {
            it.record("muse")
            it.clear()
            it.recent.first()
        }
        assertThat(afterClear).isEmpty()
    }
}
