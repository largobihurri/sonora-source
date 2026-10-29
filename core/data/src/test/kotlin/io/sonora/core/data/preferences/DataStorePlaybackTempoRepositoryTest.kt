package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The speed and pitch store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStorePlaybackTempoRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_playback_tempo.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStorePlaybackTempoRepository) -> T): T =
        withStore { block(DataStorePlaybackTempoRepository(it)) }

    @Test
    fun `nothing stored reads as normal speed and pitch`() {
        assertThat(withRepository { it.tempo.first() }).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `speed and pitch survive reopening the store`() {
        withRepository { repository -> repository.update { it.withSpeed(1.25f).withSemitones(-3) } }

        val restored = withRepository { it.tempo.first() }

        assertThat(restored.speed).isEqualTo(1.25f)
        assertThat(restored.semitones).isEqualTo(-3)
    }

    @Test
    fun `an update reaches readers at once and builds on the previous one`() {
        val seen = withRepository { repository ->
            repository.update { it.withSpeed(1.5f) }
            repository.update { it.stepPitch(2) }
            repository.tempo.first()
        }

        assertThat(seen).isEqualTo(PlaybackTempo.ofPercent(150, 2))
    }

    @Test
    fun `a reset survives reopening the store`() {
        withRepository { repository -> repository.update { it.withSpeed(0.5f).withSemitones(12) } }
        withRepository { repository -> repository.update { PlaybackTempo.DEFAULT } }

        assertThat(withRepository { it.tempo.first() }).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `stored values off the grid read back snapped and clamped`() {
        withStore { store ->
            store.edit {
                it[intPreferencesKey("tempo.speed_percent")] = 999
                it[intPreferencesKey("tempo.semitones")] = -40
            }
        }

        assertThat(withRepository { it.tempo.first() }).isEqualTo(PlaybackTempo.ofPercent(200, -12))
    }

    @Test
    fun `a write cancelled in flight is never heard`() {
        withRepository { repository -> repository.update { it.withSpeed(1.25f) } }

        val (during, after) = withStore { store ->
            coroutineScope {
                val flaky = FlakyDataStore(store)
                val repository = DataStorePlaybackTempoRepository(flaky)
                repository.tempo.first()
                flaky.stallWrites = true
                val write = launch { repository.update { it.withSpeed(1.5f) } }
                flaky.stalledWrites.first { it == 1 }
                val during = repository.tempo.first()
                write.cancelAndJoin()
                during to repository.tempo.first()
            }
        }

        assertThat(during.speedPercent).isEqualTo(125)
        assertThat(after.speedPercent).isEqualTo(125)
        assertThat(withRepository { it.tempo.first() }.speedPercent).isEqualTo(125)
    }

    @Test
    fun `a write to the file from elsewhere, such as a restore, reaches readers live`() {
        val (before, after) = withStore { store ->
            val repository = DataStorePlaybackTempoRepository(store)
            val before = repository.tempo.first()
            store.edit {
                it[intPreferencesKey("tempo.speed_percent")] = 75
                it[intPreferencesKey("tempo.semitones")] = 4
            }
            before to repository.tempo.first()
        }

        assertThat(before).isEqualTo(PlaybackTempo.DEFAULT)
        assertThat(after).isEqualTo(PlaybackTempo.ofPercent(75, 4))
    }

    @Test
    fun `basis points, Master Tempo and the range survive reopening the store`() {
        val tempo = PlaybackTempo(speedBasisPoints = 10_123, semitones = 3, masterTempo = false, range = TempoRange.SIXTEEN)
        withRepository { repository -> repository.update { tempo } }

        assertThat(withRepository { it.tempo.first() }).isEqualTo(tempo)
    }

    @Test
    fun `every write keeps the legacy whole percent key in step for an older build`() {
        val stored = withStore { store ->
            DataStorePlaybackTempoRepository(store).update { PlaybackTempo(10_050, range = TempoRange.TEN) }
            store.data.first()
        }

        assertThat(stored[intPreferencesKey("tempo.speed_percent")]).isEqualTo(101)
        assertThat(stored[intPreferencesKey("tempo.speed_basis_points")]).isEqualTo(10_050)
        assertThat(stored[booleanPreferencesKey("tempo.master_tempo")]).isTrue()
        assertThat(stored[stringPreferencesKey("tempo.range")]).isEqualTo("10")
    }

    @Test
    fun `the legacy key stays inside what an older build reads`() {
        val stored = withStore { store ->
            DataStorePlaybackTempoRepository(store).update { PlaybackTempo(PlaybackTempo.MAX_SPEED_BASIS_POINTS, range = TempoRange.WIDE) }
            store.data.first()
        }

        assertThat(stored[intPreferencesKey("tempo.speed_percent")]).isEqualTo(200)
    }

    @Test
    fun `an old backup restored over the new keys is what plays`() {
        val seen = withStore { store ->
            val repository = DataStorePlaybackTempoRepository(store)
            repository.update { PlaybackTempo(10_123, masterTempo = false, range = TempoRange.SIX) }
            store.edit {
                it[intPreferencesKey("tempo.speed_percent")] = 90
                it[intPreferencesKey("tempo.semitones")] = 2
            }
            repository.tempo.first()
        }

        assertThat(seen).isEqualTo(PlaybackTempo(9_000, semitones = 2, masterTempo = false, range = TempoRange.TEN))
    }

    @Test
    fun `an unknown stored range code reads as the fitting range`() {
        withStore { store ->
            store.edit {
                it[intPreferencesKey("tempo.speed_basis_points")] = 10_400
                it[intPreferencesKey("tempo.speed_percent")] = 104
                it[stringPreferencesKey("tempo.range")] = "from-the-future"
            }
        }

        assertThat(withRepository { it.tempo.first() }.range).isEqualTo(TempoRange.TEN)
    }

    @Test
    fun `a preview is heard at once and wins over the stored tempo`() {
        val (stored, previewed, cleared) = withRepository { repository ->
            repository.update { PlaybackTempo(10_000) }
            val stored = repository.tempo.first()
            repository.preview(PlaybackTempo(10_321, range = TempoRange.TEN))
            val previewed = repository.tempo.first()
            repository.preview(null)
            Triple(stored, previewed, repository.tempo.first())
        }

        assertThat(previewed.speedBasisPoints).isEqualTo(10_321)
        assertThat(stored.speedBasisPoints).isEqualTo(10_000)
        assertThat(cleared).isEqualTo(stored)
    }

    @Test
    fun `a preview writes nothing to the file`() {
        withRepository { repository -> repository.preview(PlaybackTempo(10_321)) }

        assertThat(withRepository { it.tempo.first() }).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `an update stores its result and clears the preview`() {
        val seen = withRepository { repository ->
            repository.preview(PlaybackTempo(10_321))
            repository.update { it.withTempoPercent(1.0) }
            repository.tempo.first()
        }

        assertThat(seen.speedBasisPoints).isEqualTo(10_100)
    }

    @Test
    fun `an update that cannot be written leaves the preview as it is`() {
        val seen = withStore { store ->
            val flaky = FlakyDataStore(store)
            val repository = DataStorePlaybackTempoRepository(flaky)
            repository.preview(PlaybackTempo(10_321))
            flaky.stallWrites = true
            coroutineScope {
                val write = launch { repository.update { it.withTempoPercent(1.0) } }
                flaky.stalledWrites.first { it == 1 }
                write.cancelAndJoin()
            }
            repository.tempo.first()
        }

        assertThat(seen.speedBasisPoints).isEqualTo(10_321)
    }
}
