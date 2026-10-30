package io.sonora.core.data.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.preferences.DataStoreVisualCollectionPreferencesRepository
import io.sonora.core.data.preferences.DataStorePreferencesRepository
import io.sonora.core.data.preferences.DataStoreEqualizerRepository
import io.sonora.core.data.preferences.DataStoreSearchHistoryRepository
import io.sonora.core.data.preferences.DataStorePlaybackTempoRepository
import io.sonora.core.data.preferences.DataStoreSortPreferencesRepository
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.port.VisualCollectionPreferencesRepository
import io.sonora.core.domain.port.EqualizerRepository
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SearchHistoryRepository
import io.sonora.core.domain.port.SortPreferencesRepository
import io.sonora.core.domain.sort.ListSorting
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app settings, list sort, equalizer, search history, speed/pitch and visual collection stores: one preferences DataStore per file and process (DataStore forbids two). */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    // File names are persisted, and a backup names its settings files by them: never rename one.
    internal const val FILE_NAME = "sonora_settings"
    internal const val SORT_FILE_NAME = "sonora_sort"
    internal const val EQUALIZER_FILE_NAME = "sonora_equalizer"
    private const val SEARCH_HISTORY_FILE_NAME = "sonora_search_history"
    internal const val TEMPO_FILE_NAME = "sonora_playback_tempo"
    internal const val VISUAL_COLLECTION_FILE_NAME = "sonora_cover_flow"

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(FILE_NAME) }

    /** List sorts live in a file of their own, so they never contend with the app settings. */
    @Provides
    @Singleton
    @SortSettingsStore
    fun provideSortDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(SORT_FILE_NAME) }

    @Provides
    @Singleton
    fun provideSortPreferences(@SortSettingsStore store: DataStore<Preferences>): SortPreferencesRepository =
        DataStoreSortPreferencesRepository(store)

    /** The equalizer has its own file too: the player reads it from the playback service. */
    @Provides
    @Singleton
    @EqualizerSettingsStore
    fun provideEqualizerDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(EQUALIZER_FILE_NAME) }

    @Provides
    @Singleton
    fun provideEqualizer(@EqualizerSettingsStore store: DataStore<Preferences>): EqualizerRepository =
        DataStoreEqualizerRepository(store)

    /** The recent searches have their own file: they change on every search and are not settings. */
    @Provides
    @Singleton
    fun provideSearchHistory(@ApplicationContext context: Context): SearchHistoryRepository =
        DataStoreSearchHistoryRepository(createSettingsDataStore { context.preferencesDataStoreFile(SEARCH_HISTORY_FILE_NAME) })

    /** Speed and pitch have their own file too: the player follows them from the playback service. */
    @Provides
    @Singleton
    @PlaybackTempoSettingsStore
    fun providePlaybackTempoDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(TEMPO_FILE_NAME) }

    @Provides
    @Singleton
    fun providePlaybackTempo(@PlaybackTempoSettingsStore store: DataStore<Preferences>): PlaybackTempoRepository =
        DataStorePlaybackTempoRepository(store)

    /** The visual collection's own settings, in a file of their own as well. */
    @Provides
    @Singleton
    @VisualCollectionSettingsStore
    fun provideVisualCollectionDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile(VISUAL_COLLECTION_FILE_NAME) }

    @Provides
    @Singleton
    fun provideVisualCollectionPreferences(@VisualCollectionSettingsStore store: DataStore<Preferences>): VisualCollectionPreferencesRepository =
        DataStoreVisualCollectionPreferencesRepository(store)

    @Provides
    fun provideListSorting(store: SortPreferencesRepository, preferences: PreferencesRepository) = ListSorting(store, preferences)
}

/**
 * The settings DataStore over [produceFile]. A file that cannot be parsed is replaced by empty preferences (every
 * setting back to its default) instead of failing every read and write until the app data is cleared.
 */
internal fun createSettingsDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w("SonoraPreferences", "Settings file corrupt; resetting to defaults", e)
        emptyPreferences()
    },
    scope = scope,
    produceFile = produceFile,
)

/** The list sorts' DataStore (the unqualified one is the app settings'). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SortSettingsStore

/** The equalizer's DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EqualizerSettingsStore

/** The speed and pitch DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackTempoSettingsStore

/** The visual collection settings' DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VisualCollectionSettingsStore

@Module
@InstallIn(SingletonComponent::class)
interface PreferencesBindings {
    @Binds
    fun bindPreferencesRepository(impl: DataStorePreferencesRepository): PreferencesRepository
}
