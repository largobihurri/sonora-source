package io.sonora.core.watchphone.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.watchlink.WearableSyncConnector
import io.sonora.core.watchphone.DefaultWatchSyncController
import io.sonora.core.watchphone.OpusTrackEncoder
import io.sonora.core.watchphone.ServiceWatchSyncForeground
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchphone.WatchSyncForeground
import io.sonora.core.watchsync.sender.TrackEncoder
import io.sonora.core.watchsync.sender.WatchSender
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
interface WatchSyncModule {

    @Binds
    fun bindController(impl: DefaultWatchSyncController): WatchSyncController

    @Binds
    fun bindForeground(impl: ServiceWatchSyncForeground): WatchSyncForeground

    @Binds
    fun bindEncoder(impl: OpusTrackEncoder): TrackEncoder

    companion object {
        @Provides
        @Singleton
        @WatchSyncScope
        fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun provideSender(@ApplicationContext context: Context, encoder: TrackEncoder): WatchSender =
            WatchSender(connector = WearableSyncConnector(context), encoder = encoder)
    }
}
