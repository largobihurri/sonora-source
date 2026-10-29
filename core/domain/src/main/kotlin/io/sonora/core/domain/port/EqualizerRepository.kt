package io.sonora.core.domain.port

import io.sonora.core.domain.equalizer.EqualizerSettings
import kotlinx.coroutines.flow.Flow

/** Persisted [EqualizerSettings], surviving restarts, apart from the app settings. */
interface EqualizerRepository {
    /** The current settings (stored, or a [preview] in progress), defaults for anything never set; re-emits on every change. */
    val settings: Flow<EqualizerSettings>

    /** Atomically replaces the settings with [transform] applied to the current ones, and stores the result. */
    suspend fun update(transform: (EqualizerSettings) -> EqualizerSettings)

    /**
     * Shows [settings] to every reader at once without storing them: a change still in progress (a band being
     * dragged), heard live but written only by the [update] that ends it.
     */
    fun preview(settings: EqualizerSettings)
}
