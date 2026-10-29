package io.sonora.core.domain.port

import kotlinx.coroutines.flow.Flow

/** The Cover Flow's own settings, kept apart from the app settings and surviving restarts. */
interface CoverFlowPreferencesRepository {
    /** Whether Cover Flow hides albums without artwork; off until the user turns it on. Re-emits on every change. */
    val hideAlbumsWithoutArtwork: Flow<Boolean>

    suspend fun setHideAlbumsWithoutArtwork(hide: Boolean)
}
