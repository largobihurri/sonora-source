package io.sonora.core.data.dub

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.PadBehaviour
import io.sonora.core.dubmodel.PreampSetup
import io.sonora.core.dubmodel.PreampWay
import io.sonora.core.dubmodel.WaySetup
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

/** The DUB setup store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreDubSetupRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_dub_setup.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreDubSetupRepository) -> T): T =
        withStore { block(DataStoreDubSetupRepository(it)) }

    private val edited = DubSetup(
        division = DubDivision.DOTTED_EIGHTH,
        feedback = 0.7f,
        returnLevel = 0.4f,
        lock = true,
        padBehaviour = PadBehaviour.TOGGLE,
        dropWays = setOf(PreampWay.SUB),
        preamp = PreampSetup()
            .with(PreampWay.SUB, WaySetup(20f, 100f, gain = 1.5f))
            .with(PreampWay.LOW_MID, WaySetup(300f, 900f, gain = 0f))
            .with(PreampWay.TOP, WaySetup(6_000f, 16_000f, gain = 0.25f)),
    )

    @Test
    fun `nothing stored reads as the defaults`() {
        val setup = withRepository { it.setup.first() }

        assertThat(setup).isEqualTo(DubSetup())
        assertThat(setup.division).isEqualTo(DubDivision.QUARTER)
        assertThat(setup.feedback).isEqualTo(0.55f)
        assertThat(setup.returnLevel).isEqualTo(0.80f)
    }

    @Test
    fun `an update is stored and survives reopening the store`() {
        withRepository { repository -> repository.update { edited } }

        assertThat(withRepository { it.setup.first() }).isEqualTo(edited)
    }

    @Test
    fun `updates apply to the current setup`() {
        withRepository { repository ->
            repository.update { it.copy(feedback = 0.3f) }
            repository.update { it.copy(preamp = it.preamp.with(PreampWay.BASS, it.preamp[PreampWay.BASS].withLevelDb(-6f))) }

            val setup = repository.setup.first()
            assertThat(setup.feedback).isEqualTo(0.3f)
            assertThat(setup.preamp[PreampWay.BASS].levelDb).isWithin(1e-3f).of(-6f)
        }
    }

    @Test
    fun `a preview reaches readers at once and is not stored`() {
        withRepository { repository ->
            repository.setup.first()
            repository.preview(edited)

            assertThat(repository.setup.first()).isEqualTo(edited)
        }

        assertThat(withRepository { it.setup.first() }).isEqualTo(DubSetup())
    }

    @Test
    fun `the update that ends a drag stores what it sets`() {
        withRepository { repository ->
            repository.preview(DubSetup(feedback = 0.9f))
            repository.update { it.copy(feedback = 0.8f) }
        }

        assertThat(withRepository { it.setup.first() }.feedback).isEqualTo(0.8f)
    }

    @Test
    fun `a corrupt store falls back to the defaults`() {
        file.writeBytes(byteArrayOf(0x7f, 0x13, 0x00, 0x42, 0x2a, 0x01))

        assertThat(withRepository { it.setup.first() }).isEqualTo(DubSetup())
    }

    @Test
    fun `unknown or out of range stored values read as defaults or clamped`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("dub.division")] = "THIRTY_SECOND"
                it[stringPreferencesKey("dub.pads")] = "SWIPE"
                it[floatPreferencesKey("dub.feedback")] = 3f
                it[floatPreferencesKey("dub.return")] = Float.NaN
                it[stringSetPreferencesKey("dub.drop.ways")] = setOf("BASS", "SUBSONIC")
                it[floatPreferencesKey("dub.preamp.TOP.hp")] = 50_000f
                it[floatPreferencesKey("dub.preamp.TOP.lp")] = 1_000f
                it[floatPreferencesKey("dub.preamp.SUB.gain")] = 7f
            }
        }

        val setup = withRepository { it.setup.first() }

        assertThat(setup.division).isEqualTo(DubDivision.QUARTER)
        assertThat(setup.padBehaviour).isEqualTo(PadBehaviour.HOLD_DOUBLE_TAP_LATCH)
        assertThat(setup.feedback).isEqualTo(DubSetup.MAX_FEEDBACK)
        assertThat(setup.returnLevel).isEqualTo(DubSetup.DEFAULT_RETURN)
        assertThat(setup.dropWays).containsExactly(PreampWay.BASS)
        assertThat(setup.preamp[PreampWay.TOP]).isEqualTo(WaySetup(1_000f, 20_000f))
        assertThat(setup.preamp[PreampWay.SUB].gain).isEqualTo(WaySetup.MAX_GAIN)
        assertThat(setup.preamp[PreampWay.BASS]).isEqualTo(WaySetup.defaultFor(PreampWay.BASS))
    }

    @Test
    fun `a preview and an update are clamped to the valid ranges`() {
        withRepository { repository ->
            repository.preview(DubSetup(feedback = 2f))
            assertThat(repository.setup.first().feedback).isEqualTo(DubSetup.MAX_FEEDBACK)

            repository.update { it.copy(returnLevel = -1f, feedback = Float.NaN) }
        }

        val stored = withRepository { it.setup.first() }
        assertThat(stored.returnLevel).isEqualTo(0f)
        assertThat(stored.feedback).isEqualTo(DubSetup.DEFAULT_FEEDBACK)
    }

    @Test
    fun `DROP may kill no way at all, and that choice is kept`() {
        withRepository { repository -> repository.update { it.copy(dropWays = emptySet()) } }

        assertThat(withRepository { it.setup.first() }.dropWays).isEmpty()
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
            val repository = DataStoreDubSetupRepository(writes)
            repository.update { it.copy(feedback = 0.4f) }
            val stored = repository.setup.first()
            writes.failing = true

            repository.preview(stored.copy(feedback = 0.9f))
            assertThrows(IOException::class.java) {
                runBlocking { repository.update { it.copy(feedback = 0.9f) } }
            }

            assertThat(repository.setup.first()).isEqualTo(stored)
        }
    }
}
