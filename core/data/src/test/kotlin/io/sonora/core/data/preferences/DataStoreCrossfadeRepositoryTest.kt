package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.playback.Crossfade
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

/** The crossfade store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreCrossfadeRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_crossfade.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreCrossfadeRepository) -> T): T =
        withStore { block(DataStoreCrossfadeRepository(it)) }

    @Test
    fun `nothing stored reads as off`() {
        assertThat(withRepository { it.seconds.first() }).isEqualTo(Crossfade.DEFAULT_SECONDS)
        assertThat(Crossfade.DEFAULT_SECONDS).isEqualTo(0)
    }

    @Test
    fun `the chosen length survives reopening the store`() {
        withRepository { it.setSeconds(7) }

        assertThat(withRepository { it.seconds.first() }).isEqualTo(7)
    }

    @Test
    fun `a length outside zero to twelve seconds is saved at the nearest end`() {
        withRepository { it.setSeconds(40) }
        assertThat(withRepository { it.seconds.first() }).isEqualTo(Crossfade.MAX_SECONDS)

        withRepository { it.setSeconds(-2) }
        assertThat(withRepository { it.seconds.first() }).isEqualTo(Crossfade.MIN_SECONDS)
    }

    @Test
    fun `a stored value out of range reads at the nearest end`() {
        withStore { store -> store.edit { it[intPreferencesKey("playback.crossfade.seconds")] = 99 } }

        assertThat(withRepository { it.seconds.first() }).isEqualTo(Crossfade.MAX_SECONDS)
    }
}
