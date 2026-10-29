package io.sonora.core.domain.playback

import kotlinx.coroutines.flow.Flow

/** Persisted [ReplayGainSettings], surviving restarts, apart from the app settings. */
interface ReplayGainRepository {
    /** The current settings, defaults for anything never set; re-emits on every change. */
    val settings: Flow<ReplayGainSettings>

    /** Atomically replaces the settings with [transform] applied to the current ones, and stores the result. */
    suspend fun update(transform: (ReplayGainSettings) -> ReplayGainSettings)
}
