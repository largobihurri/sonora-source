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

/** The Cover Flow settings against a real preferences DataStore file of their own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreCoverFlowPreferencesRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_cover_flow.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreCoverFlowPreferencesRepository) -> T): T =
        withStore { block(DataStoreCoverFlowPreferencesRepository(it)) }

    @Test
    fun `albums without artwork are shown until the user hides them`() {
        assertThat(withRepository { it.hideAlbumsWithoutArtwork.first() }).isFalse()
    }

    @Test
    fun `hiding albums without artwork survives reopening the store`() {
        withRepository { it.setHideAlbumsWithoutArtwork(true) }

        assertThat(withRepository { it.hideAlbumsWithoutArtwork.first() }).isTrue()
    }

    @Test
    fun `turning the switch off again shows them again after reopening`() {
        withRepository {
            it.setHideAlbumsWithoutArtwork(true)
            it.setHideAlbumsWithoutArtwork(false)
        }

        assertThat(withRepository { it.hideAlbumsWithoutArtwork.first() }).isFalse()
    }

    @Test
    fun `an unreadable file reads as the default`() {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))

        assertThat(withRepository { it.hideAlbumsWithoutArtwork.first() }).isFalse()
    }
}
