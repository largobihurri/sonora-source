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
import io.sonora.core.data.preferences.DataStoreCrossfadeRepository
import io.sonora.core.domain.playback.CrossfadeRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/** The crossfade length, in a DataStore file of its own like the equalizer: the playback service reads it. */
@Module
@InstallIn(SingletonComponent::class)
object CrossfadeModule {
    // The file name is persisted, and a backup names the file by it: never rename it.
    internal const val FILE_NAME = "sonora_crossfade"

    @Provides
    @Singleton
    @CrossfadeSettingsStore
    fun provideCrossfadeDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(FILE_NAME) }

    @Provides
    @Singleton
    fun provideCrossfade(@CrossfadeSettingsStore store: DataStore<Preferences>): CrossfadeRepository = DataStoreCrossfadeRepository(store)
}

/** The crossfade length's DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrossfadeSettingsStore
