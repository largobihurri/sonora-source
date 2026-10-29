package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The volume normalisation store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreReplayGainRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_replay_gain.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreReplayGainRepository) -> T): T =
        withStore { block(DataStoreReplayGainRepository(it)) }

    @Test
    fun `nothing stored reads as off with no pre-amp`() {
        assertThat(withRepository { it.settings.first() }).isEqualTo(ReplayGainSettings())
    }

    @Test
    fun `mode and pre-amp survive reopening the store`() {
        withRepository { repository -> repository.update { it.copy(mode = ReplayGainMode.AUTO, preampDb = -2.5f) } }

        assertThat(withRepository { it.settings.first() }).isEqualTo(ReplayGainSettings(ReplayGainMode.AUTO, -2.5f))
    }

    @Test
    fun `an update reaches readers at once`() {
        val seen = withRepository { repository ->
            repository.update { it.copy(mode = ReplayGainMode.TRACK) }
            repository.update { it.copy(preampDb = 3f) }
            repository.settings.first()
        }

        assertThat(seen).isEqualTo(ReplayGainSettings(ReplayGainMode.TRACK, 3f))
    }

    @Test
    fun `unknown or out-of-range stored values read as their defaults or limits`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("replay_gain.mode")] = "LOUDER"
                it[floatPreferencesKey("replay_gain.preamp")] = 40f
            }
        }

        assertThat(withRepository { it.settings.first() }).isEqualTo(ReplayGainSettings(ReplayGainMode.OFF, 6f))
    }

    @Test
    fun `a read that fails once is not kept, the next read and the next update see the file`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("replay_gain.mode")] = "TRACK"
                it[floatPreferencesKey("replay_gain.preamp")] = 2f
            }
        }

        val (first, second) = withStore { store ->
            val repository = DataStoreReplayGainRepository(FlakyDataStore(store).apply { failReads = 1 })
            repository.settings.first() to repository.settings.first()
        }
        withStore { store ->
            val repository = DataStoreReplayGainRepository(FlakyDataStore(store).apply { failReads = 1 })
            repository.settings.first()
            repository.update { it.copy(preampDb = 3f) }
        }

        assertThat(first).isEqualTo(ReplayGainSettings())
        assertThat(second).isEqualTo(ReplayGainSettings(ReplayGainMode.TRACK, 2f))
        assertThat(withRepository { it.settings.first() }).isEqualTo(ReplayGainSettings(ReplayGainMode.TRACK, 3f))
    }

    @Test
    fun `a write to the file from elsewhere, such as a restore, reaches readers live`() {
        val (before, after) = withStore { store ->
            val repository = DataStoreReplayGainRepository(store)
            val before = repository.settings.first()
            store.edit {
                it[stringPreferencesKey("replay_gain.mode")] = "ALBUM"
                it[floatPreferencesKey("replay_gain.preamp")] = -1.5f
            }
            before to repository.settings.first()
        }

        assertThat(before).isEqualTo(ReplayGainSettings())
        assertThat(after).isEqualTo(ReplayGainSettings(ReplayGainMode.ALBUM, -1.5f))
    }

    @Test
    fun `a reader kept on the defaults by an unreadable file moves to the file once it reads`() {
        withStore { store -> store.edit { it[stringPreferencesKey("replay_gain.mode")] = "TRACK" } }

        val seen = withStore { store ->
            val repository = DataStoreReplayGainRepository(FlakyDataStore(store).apply { failReads = 1 }, readRetryMs = 10)
            repository.settings.take(2).toList()
        }

        assertThat(seen).containsExactly(ReplayGainSettings(), ReplayGainSettings(ReplayGainMode.TRACK)).inOrder()
    }
}
