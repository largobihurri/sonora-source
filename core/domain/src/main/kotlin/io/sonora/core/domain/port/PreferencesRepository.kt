package io.sonora.core.domain.port

import io.sonora.core.domain.model.AppPreferences
import kotlinx.coroutines.flow.Flow

/** Persisted [AppPreferences], surviving restarts. */
interface PreferencesRepository {
    /** The current preferences, defaults for anything never set; re-emits on every change. */
    val preferences: Flow<AppPreferences>

    /** Atomically replaces the preferences with [transform] applied to the stored ones. */
    suspend fun update(transform: (AppPreferences) -> AppPreferences)
}
