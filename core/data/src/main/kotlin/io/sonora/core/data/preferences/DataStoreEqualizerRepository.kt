package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.port.EqualizerRepository
import java.io.IOException
import java.util.Locale
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
 * [EqualizerRepository] in a preferences DataStore file of its own: `equalizer.enabled`, `equalizer.mode` (the enum
 * name), `equalizer.gains.<mode>` (the band gains in dB, comma-separated, `.` as the decimal mark) and
 * `equalizer.preamp.automatic`. A missing, unknown or unreadable value reads as its default (so a store from before
 * the automatic preamp switch reads with it on).
 *
 * The file is read once; from then on the settings live in memory (this process is the only writer), so a
 * [preview] reaches the player at once while only [update] writes the file. An update that cannot be written throws
 * and puts readers back on what the file holds, so what is heard never differs from what the next start loads.
 */
class DataStoreEqualizerRepository(
    private val dataStore: DataStore<Preferences>,
) : EqualizerRepository {

    private val current = MutableStateFlow<EqualizerSettings?>(null)
    private val writes = Mutex()

    /** What the file holds, once read or written here; guarded by [writes]. */
    private var persisted: EqualizerSettings? = null

    override val settings: Flow<EqualizerSettings> = flow {
        if (current.value == null) current.compareAndSet(null, stored())
        emitAll(current.filterNotNull())
    }.distinctUntilChanged()

    override suspend fun update(transform: (EqualizerSettings) -> EqualizerSettings) {
        writes.withLock {
            val saved = persisted ?: stored().also { persisted = it }
            val next = transform(current.value ?: saved)
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

    override fun preview(settings: EqualizerSettings) {
        current.value = settings
    }

    private suspend fun stored(): EqualizerSettings = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Equalizer settings unreadable; using defaults", e)
            emit(emptyPreferences())
        }
        .map(::read)
        .first()

    private fun read(stored: Preferences): EqualizerSettings = EqualizerSettings(
        enabled = stored[ENABLED] ?: false,
        mode = stored[MODE]?.let { name -> EqualizerMode.entries.firstOrNull { it.name == name } } ?: EqualizerMode.FOUR_BANDS,
        gains = EqualizerMode.entries.associateWith { mode ->
            stored[gainsKey(mode)].orEmpty().split(',').map { it.trim().toFloatOrNull() ?: 0f }
        },
        automaticPreamp = stored[AUTOMATIC_PREAMP] ?: true,
    )

    private fun write(stored: MutablePreferences, settings: EqualizerSettings) {
        stored[ENABLED] = settings.enabled
        stored[MODE] = settings.mode.name
        stored[AUTOMATIC_PREAMP] = settings.automaticPreamp
        for (mode in EqualizerMode.entries) {
            stored[gainsKey(mode)] = settings.gainsFor(mode).joinToString(",") { String.format(Locale.ROOT, "%.1f", it) }
        }
    }

    private companion object {
        const val TAG = "SonoraEqualizer"

        // Key names are persisted: never change them.
        val ENABLED = booleanPreferencesKey("equalizer.enabled")
        val MODE = stringPreferencesKey("equalizer.mode")
        val AUTOMATIC_PREAMP = booleanPreferencesKey("equalizer.preamp.automatic")

        fun gainsKey(mode: EqualizerMode) = stringPreferencesKey("equalizer.gains.${mode.name}")
    }
}
