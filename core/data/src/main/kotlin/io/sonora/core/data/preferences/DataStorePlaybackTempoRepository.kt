package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * [PlaybackTempoRepository] in a preferences DataStore file of its own, under the keys of [TempoKeys]: the exact speed
 * in basis points, Master Tempo, the fader range and the semitones, plus the legacy whole-percent speed every write
 * keeps in step. A missing or unreadable value reads as normal speed and pitch; values that do not agree or fit are
 * resolved by [TempoKeys.read].
 *
 * The setting follows the file: a change stored here or by anyone else writing this store (a backup restore) reaches
 * the player as soon as it is stored. Readers only ever see what the file holds, so an update that fails or is
 * cancelled mid-write is never heard, and what is heard never differs from what the next start loads. The one
 * exception is [preview], an in-memory tempo (this process only) that wins over the file until it is cleared, which a
 * successful [update] does. A file that cannot be read plays normal speed while it is read again every [readRetryMs];
 * an update builds on what the file holds (never on a preview) and throws when it cannot be written.
 */
class DataStorePlaybackTempoRepository(
    private val dataStore: DataStore<Preferences>,
    private val readRetryMs: Long = READ_RETRY_MS,
) : PlaybackTempoRepository {

    private val previewed = MutableStateFlow<PlaybackTempo?>(null)

    private val stored: Flow<PlaybackTempo> = dataStore.data
        .map(::tempoOf)
        .retryWhen { e, _ ->
            if (e !is IOException) return@retryWhen false
            Log.w(TAG, "Speed and pitch unreadable; using normal for now", e)
            emit(PlaybackTempo.DEFAULT)
            delay(readRetryMs)
            true
        }

    override val tempo: Flow<PlaybackTempo> = combine(stored, previewed) { file, preview -> preview ?: file }.distinctUntilChanged()

    override fun preview(tempo: PlaybackTempo?) {
        previewed.value = tempo
    }

    override suspend fun update(transform: (PlaybackTempo) -> PlaybackTempo) {
        dataStore.edit {
            val next = transform(tempoOf(it))
            it[TempoKeys.SPEED_BASIS_POINTS] = next.speedBasisPoints
            it[TempoKeys.MASTER_TEMPO] = next.masterTempo
            it[TempoKeys.RANGE] = next.range.code
            it[TempoKeys.SPEED_PERCENT] = TempoKeys.legacyPercentOf(next.speedBasisPoints)
            it[TempoKeys.SEMITONES] = next.semitones
        }
        previewed.value = null
    }

    private fun tempoOf(prefs: Preferences) = TempoKeys.read(
        legacyPercent = prefs[TempoKeys.SPEED_PERCENT],
        speedBasisPoints = prefs[TempoKeys.SPEED_BASIS_POINTS],
        masterTempo = prefs[TempoKeys.MASTER_TEMPO],
        rangeCode = prefs[TempoKeys.RANGE],
        semitones = prefs[TempoKeys.SEMITONES],
    )

    private companion object {
        const val TAG = "SonoraTempo"

        /** How long an unreadable file plays normal speed before it is read again. */
        const val READ_RETRY_MS = 5_000L
    }
}
