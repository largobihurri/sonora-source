package io.sonora.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.backup.BackupJson
import io.sonora.core.data.backup.DataStoreBackupHistory
import io.sonora.core.data.backup.DataStoreSettingsBackup
import io.sonora.core.data.backup.RoomLibraryBackupStore
import io.sonora.core.data.backup.SafBackupDocuments
import io.sonora.core.data.backup.db.BackupDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.backup.ApplyRestore
import io.sonora.core.domain.backup.CreateBackup
import io.sonora.core.domain.backup.ReadBackup
import io.sonora.core.domain.port.BackupCodec
import io.sonora.core.domain.port.BackupDocuments
import io.sonora.core.domain.port.BackupHistory
import io.sonora.core.domain.port.DjBackupStore
import io.sonora.core.domain.port.LibraryBackupStore
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.SettingsBackupStore
import java.time.Clock
import java.util.Optional
import javax.inject.Singleton

/** Settings › Backup & Restore: the library database, the settings files and the picked documents. */
@Module
@InstallIn(SingletonComponent::class)
interface BackupBindings {
    @Binds
    fun bindLibraryBackupStore(impl: RoomLibraryBackupStore): LibraryBackupStore

    @Binds
    fun bindBackupCodec(impl: BackupJson): BackupCodec

    @Binds
    fun bindBackupDocuments(impl: SafBackupDocuments): BackupDocuments

    /**
     * The DJ store's share of a backup is bound by `:core:djstore` when it is part of the app, so a build without it
     * still backs up and restores everything else.
     */
    @BindsOptionalOf
    fun optionalDjBackupStore(): DjBackupStore
}

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    private const val HISTORY_FILE_NAME = "sonora_backup"

    @Provides
    fun provideBackupDao(database: LibraryDatabase): BackupDao = database.backupDao()

    /**
     * The settings files a backup carries, by the names it stores them under. The output folder's grant, the search
     * history and the backup history are not among them. A backup without one of these files
     * (written before it was carried) leaves it as it is on restore.
     */
    @Provides
    @Singleton
    fun provideSettingsBackup(
        settings: DataStore<Preferences>,
        @SortSettingsStore sort: DataStore<Preferences>,
        @EqualizerSettingsStore equalizer: DataStore<Preferences>,
        @PlaybackTempoSettingsStore tempo: DataStore<Preferences>,
        @ReplayGainSettingsStore replayGain: DataStore<Preferences>,
        @CrossfadeSettingsStore crossfade: DataStore<Preferences>,
        @VisualCollectionSettingsStore visualCollection: DataStore<Preferences>,
    ): SettingsBackupStore = DataStoreSettingsBackup(
        mapOf(
            PreferencesModule.FILE_NAME to settings,
            PreferencesModule.SORT_FILE_NAME to sort,
            PreferencesModule.EQUALIZER_FILE_NAME to equalizer,
            PreferencesModule.TEMPO_FILE_NAME to tempo,
            ReplayGainModule.FILE_NAME to replayGain,
            CrossfadeModule.FILE_NAME to crossfade,
            PreferencesModule.VISUAL_COLLECTION_FILE_NAME to visualCollection,
        ),
    )

    @Provides
    @Singleton
    fun provideBackupHistory(@ApplicationContext context: Context): BackupHistory =
        DataStoreBackupHistory(createSettingsDataStore { context.preferencesDataStoreFile(HISTORY_FILE_NAME) })

    @Provides
    fun provideCreateBackup(
        store: LibraryBackupStore,
        settings: SettingsBackupStore,
        playlists: PlaylistRepository,
        codec: BackupCodec,
        documents: BackupDocuments,
        history: BackupHistory,
        clock: Clock,
        djStore: Optional<DjBackupStore>,
    ) = CreateBackup(store, settings, playlists, codec, documents, history, now = clock::millis, djStore = djStore.orElse(null))

    @Provides
    fun provideReadBackup(store: LibraryBackupStore, playlists: PlaylistRepository, codec: BackupCodec, documents: BackupDocuments) =
        ReadBackup(store, playlists, codec, documents)

    @Provides
    fun provideApplyRestore(
        store: LibraryBackupStore,
        settings: SettingsBackupStore,
        playlists: PlaylistRepository,
        djStore: Optional<DjBackupStore>,
    ) = ApplyRestore(store, settings, playlists, djStore.orElse(null))
}
