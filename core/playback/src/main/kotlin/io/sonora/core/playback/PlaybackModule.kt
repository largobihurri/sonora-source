package io.sonora.core.playback

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.playback.AbLoopController
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.LoopClockSource
import io.sonora.core.domain.playback.PlaybackBandLevels
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.QueuedTrackUpdater
import io.sonora.core.domain.port.VolumeController
import io.sonora.core.playback.clock.LivePlaybackClock
import io.sonora.core.playback.levels.LiveBandLevels
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.playback.dub.DubController
import io.sonora.core.playback.loop.LoopController
import io.sonora.core.playback.output.AudioManagerOutputMonitor

@Module
@InstallIn(SingletonComponent::class)
interface PlaybackModule {
    @Binds
    fun bindPlaybackController(impl: Media3PlaybackController): PlaybackController

    @Binds
    fun bindQueuedTrackUpdater(impl: Media3PlaybackController): QueuedTrackUpdater

    @Binds
    fun bindAbLoopController(impl: Media3PlaybackController): AbLoopController

    @Binds
    fun bindVolumeController(impl: SystemVolumeController): VolumeController

    @Binds
    fun bindPlaybackBandLevels(impl: LiveBandLevels): PlaybackBandLevels

    @Binds
    fun bindPlaybackClock(impl: LivePlaybackClock): PlaybackClock

    @Binds
    fun bindDjLoopController(impl: LoopController): DjLoopController

    @Binds
    fun bindLoopClockSource(impl: LoopController): LoopClockSource

    @Binds
    fun bindDubStage(impl: DubController): DubStage

    @Binds
    fun bindSleepTimer(impl: SleepTimerService): SleepTimer

    @Binds
    fun bindAudioOutputMonitor(impl: AudioManagerOutputMonitor): AudioOutputMonitor

    /** Bound only in builds that can hand the session to another device (Chromecast in `full`). */
    @BindsOptionalOf
    fun optionalSessionRouter(): SessionRouter
}
