package io.sonora.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.preferences.DataStoreReplayGainRepository
import io.sonora.core.domain.playback.ReplayGainRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/** The volume normalisation settings, in a DataStore file of their own: the player reads them from the playback service. */
@Module
@InstallIn(SingletonComponent::class)
object ReplayGainModule {
    // The file name is persisted, and a backup names the file by it: never rename it.
    internal const val FILE_NAME = "sonora_replay_gain"

    @Provides
    @Singleton
    @ReplayGainSettingsStore
    fun provideReplayGainDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(FILE_NAME) }

    @Provides
    @Singleton
    fun provideReplayGain(@ReplayGainSettingsStore store: DataStore<Preferences>): ReplayGainRepository = DataStoreReplayGainRepository(store)
}

/** The volume normalisation settings' DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReplayGainSettingsStore
