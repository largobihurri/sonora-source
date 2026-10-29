package io.sonora.core.watchphone.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.watchlink.WearableRemoteLink
import io.sonora.core.watchphone.remote.AudioManagerRemoteVolume
import io.sonora.core.watchphone.remote.RemoteVolume
import io.sonora.core.watchsync.remote.RemoteLink
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The application-lifetime scope the watch remote runs on: the main thread, where the player is driven from. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RemoteScope

@Module
@InstallIn(SingletonComponent::class)
interface WatchRemoteModule {

    @Binds
    fun bindVolume(impl: AudioManagerRemoteVolume): RemoteVolume

    companion object {
        @Provides
        @Singleton
        @RemoteScope
        fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @Provides
        @Singleton
        fun provideLink(@ApplicationContext context: Context): RemoteLink = WearableRemoteLink(context)
    }
}
