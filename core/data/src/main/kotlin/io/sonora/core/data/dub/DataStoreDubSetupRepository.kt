package io.sonora.core.data.dub

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.PadBehaviour
import io.sonora.core.dubmodel.PreampSetup
import io.sonora.core.dubmodel.PreampWay
import io.sonora.core.dubmodel.WaySetup
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DubSetupRepository] in a preferences DataStore file of its own: `dub.division` and `dub.pads` (enum names),
 * `dub.feedback` and `dub.return` (0..1), `dub.lock`, `dub.drop.ways` (the [PreampWay] names DROP kills) and, per way,
 * `dub.preamp.<WAY>.hp`, `.lp` (Hz) and `.gain` (linear). A missing, unknown or unreadable value reads as its default,
 * every value is clamped to its valid range ([DubSetup.clamped]), and a corrupt file reads as the defaults.
 *
 * The file is read once; from then on the setup lives in memory (this process is the only writer), so a [preview]
 * reaches the DUB stage at once while only [update] writes the file. An update that cannot be written throws and puts
 * readers back on what the file holds, so what is heard never differs from what the next start loads.
 */
class DataStoreDubSetupRepository(
    private val dataStore: DataStore<Preferences>,
) : DubSetupRepository {

    private val current = MutableStateFlow<DubSetup?>(null)
    private val writes = Mutex()

    /** What the file holds, once read or written here; guarded by [writes]. */
    private var persisted: DubSetup? = null

    override val setup: Flow<DubSetup> = flow {
        if (current.value == null) current.compareAndSet(null, stored())
        emitAll(current.filterNotNull())
    }.distinctUntilChanged()

    override suspend fun update(transform: (DubSetup) -> DubSetup) {
        writes.withLock {
            val saved = persisted ?: stored().also { persisted = it }
            val next = transform(current.value ?: saved).clamped()
            current.value = next
            try {
                dataStore.edit { write(it, next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.value = saved
                throw e
            }
            persisted = next
        }
    }

    override fun preview(setup: DubSetup) {
        current.value = setup.clamped()
    }

    private suspend fun stored(): DubSetup = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "DUB setup unreadable; using defaults", e)
            emit(emptyPreferences())
        }
        .map(::read)
        .first()

    private fun read(stored: Preferences): DubSetup {
        val defaults = DubSetup()
        return DubSetup(
            division = stored[DIVISION]?.let { name -> DubDivision.entries.firstOrNull { it.name == name } } ?: defaults.division,
            feedback = stored[FEEDBACK] ?: defaults.feedback,
            returnLevel = stored[RETURN] ?: defaults.returnLevel,
            lock = stored[LOCK] ?: defaults.lock,
            padBehaviour = stored[PADS]?.let { name -> PadBehaviour.entries.firstOrNull { it.name == name } } ?: defaults.padBehaviour,
            dropWays = stored[DROP_WAYS]?.let { names -> PreampWay.entries.filterTo(mutableSetOf()) { it.name in names } }
                ?: defaults.dropWays,
            preamp = PreampSetup(
                PreampWay.entries.map { way ->
                    val default = WaySetup.defaultFor(way)
                    WaySetup(
                        highPassHz = stored[highPassKey(way)] ?: default.highPassHz,
                        lowPassHz = stored[lowPassKey(way)] ?: default.lowPassHz,
                        gain = stored[gainKey(way)] ?: default.gain,
                    )
                },
            ),
        ).clamped()
    }

    private fun write(stored: MutablePreferences, setup: DubSetup) {
        stored[DIVISION] = setup.division.name
        stored[FEEDBACK] = setup.feedback
        stored[RETURN] = setup.returnLevel
        stored[LOCK] = setup.lock
        stored[PADS] = setup.padBehaviour.name
        stored[DROP_WAYS] = setup.dropWays.mapTo(mutableSetOf()) { it.name }
        for (way in PreampWay.entries) {
            val waySetup = setup.preamp[way]
            stored[highPassKey(way)] = waySetup.highPassHz
            stored[lowPassKey(way)] = waySetup.lowPassHz
            stored[gainKey(way)] = waySetup.gain
        }
    }

    private companion object {
        const val TAG = "SonoraDub"

        // Key names and the enum names stored in them are persisted: never change them.
        val DIVISION = stringPreferencesKey("dub.division")
        val FEEDBACK = floatPreferencesKey("dub.feedback")
        val RETURN = floatPreferencesKey("dub.return")
        val LOCK = booleanPreferencesKey("dub.lock")
        val PADS = stringPreferencesKey("dub.pads")
        val DROP_WAYS = stringSetPreferencesKey("dub.drop.ways")

        fun highPassKey(way: PreampWay) = floatPreferencesKey("dub.preamp.${way.name}.hp")
        fun lowPassKey(way: PreampWay) = floatPreferencesKey("dub.preamp.${way.name}.lp")
        fun gainKey(way: PreampWay) = floatPreferencesKey("dub.preamp.${way.name}.gain")
    }
}
