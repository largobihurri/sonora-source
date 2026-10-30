package io.sonora.core.domain.port

import kotlinx.coroutines.flow.Flow

/** The visual collection's own settings, kept apart from the app settings and surviving restarts. */
interface VisualCollectionPreferencesRepository {
    /** Whether visual collection hides albums without artwork; off until the user turns it on. Re-emits on every change. */
    val hideAlbumsWithoutArtwork: Flow<Boolean>

    suspend fun setHideAlbumsWithoutArtwork(hide: Boolean)
}
