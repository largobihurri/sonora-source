package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The equalizer store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreEqualizerRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_equalizer.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreEqualizerRepository) -> T): T =
        withStore { block(DataStoreEqualizerRepository(it)) }

    @Test
    fun `nothing stored reads as the defaults`() {
        assertThat(withRepository { it.settings.first() }).isEqualTo(EqualizerSettings())
    }

    @Test
    fun `switch, mode and every mode's gains survive reopening the store`() {
        val saved = EqualizerSettings(enabled = true, mode = EqualizerMode.TWENTY_BANDS)
            .withGain(EqualizerMode.FOUR_BANDS, 0, 4.5f)
            .withGain(EqualizerMode.ELEVEN_BANDS, 10, -12f)
            .withGain(EqualizerMode.TWENTY_BANDS, 7, 0.1f)
        withRepository { repository -> repository.update { saved } }

        val restored = withRepository { it.settings.first() }

        assertThat(restored).isEqualTo(saved)
        assertThat(restored.gainsFor(EqualizerMode.ELEVEN_BANDS)[10]).isEqualTo(-12f)
    }

    @Test
    fun `the automatic preamp switched off survives reopening the store`() {
        withRepository { repository -> repository.update { it.withEnabled(true).withAutomaticPreamp(false) } }

        val restored = withRepository { it.settings.first() }

        assertThat(restored.automaticPreamp).isFalse()
        assertThat(restored.enabled).isTrue()
    }

    @Test
    fun `settings stored before the automatic preamp switch existed read with it on`() {
        withStore { store ->
            store.edit {
                it[booleanPreferencesKey("equalizer.enabled")] = true
                it[stringPreferencesKey("equalizer.mode")] = "ELEVEN_BANDS"
                it[stringPreferencesKey("equalizer.gains.ELEVEN_BANDS")] = "0.0,0.0,6.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0"
            }
        }

        val settings = withRepository { it.settings.first() }

        assertThat(settings.automaticPreamp).isTrue()
        assertThat(settings.enabled).isTrue()
        assertThat(settings.activeGains[2]).isEqualTo(6f)
    }

    @Test
    fun `updates apply to the stored settings`() {
        withRepository { repository ->
            repository.update { it.withEnabled(true) }
            repository.update { it.withGain(EqualizerMode.FOUR_BANDS, 2, 3f) }

            val settings = repository.settings.first()
            assertThat(settings.enabled).isTrue()
            assertThat(settings.activeGains).containsExactly(0f, 0f, 3f, 0f).inOrder()
        }
    }

    @Test
    fun `an unknown mode or unreadable gains fall back to the defaults`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("equalizer.mode")] = "THIRTY_BANDS"
                it[stringPreferencesKey("equalizer.gains.FOUR_BANDS")] = "1.0,abc,,3"
            }
        }

        val settings = withRepository { it.settings.first() }

        assertThat(settings.mode).isEqualTo(EqualizerMode.FOUR_BANDS)
        assertThat(settings.gainsFor(EqualizerMode.FOUR_BANDS)).containsExactly(1f, 0f, 0f, 3f).inOrder()
    }

    @Test
    fun `a preview reaches readers at once and is stored only by the update that ends it`() {
        val dragged = EqualizerSettings(enabled = true).withGain(EqualizerMode.FOUR_BANDS, 1, 6f)
        withRepository { repository ->
            repository.settings.first()
            repository.preview(dragged)

            assertThat(repository.settings.first()).isEqualTo(dragged)
        }
        assertThat(withRepository { it.settings.first() }).isEqualTo(EqualizerSettings())

        withRepository { repository ->
            repository.preview(dragged)
            repository.update { it.withGain(EqualizerMode.FOUR_BANDS, 1, 5f) }
        }
        assertThat(withRepository { it.settings.first() }.activeGains).containsExactly(0f, 5f, 0f, 0f).inOrder()
    }

    /** The real store, whose writes fail like a full disk while [failing]. */
    private class FailingWrites(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        var failing = false
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failing) throw IOException("disk full")
            return inner.updateData(transform)
        }
    }

    @Test
    fun `a change that cannot be written is not kept in memory either`() {
        withStore { store ->
            val writes = FailingWrites(store)
            val repository = DataStoreEqualizerRepository(writes)
            repository.update { it.withEnabled(true) }
            val stored = repository.settings.first()
            writes.failing = true

            repository.preview(stored.withGain(EqualizerMode.FOUR_BANDS, 1, 6f))
            assertThrows(IOException::class.java) {
                runBlocking { repository.update { it.withGain(EqualizerMode.FOUR_BANDS, 1, 6f) } }
            }

            // Readers (the player, the screen) go back to what the file holds.
            assertThat(repository.settings.first()).isEqualTo(stored)
            writes.failing = false
            repository.update { it.withGain(EqualizerMode.FOUR_BANDS, 2, 3f) }
            assertThat(repository.settings.first().activeGains).containsExactly(0f, 0f, 3f, 0f).inOrder()
        }
    }
}
