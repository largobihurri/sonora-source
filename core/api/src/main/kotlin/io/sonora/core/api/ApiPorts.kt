package io.sonora.core.api

import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository

/** The domain ports the external API runs over; the provider adapter builds it once from the injected ones. */
class ApiPorts(
    val playback: PlaybackController,
    val sleepTimer: SleepTimer,
    val tempo: PlaybackTempoRepository,
    val crossfade: CrossfadeRepository,
    val library: LibraryRepository,
    val playlists: PlaylistRepository,
    val djMode: DjModeSettings,
)
