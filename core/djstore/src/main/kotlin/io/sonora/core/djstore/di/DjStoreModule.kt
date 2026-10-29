package io.sonora.core.djstore.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djmodel.DjCueStore
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djstore.RoomAudioIdIndex
import io.sonora.core.djstore.RoomDjBackupStore
import io.sonora.core.djstore.RoomDjCueStore
import io.sonora.core.djstore.RoomDjGridEditStore
import io.sonora.core.djstore.RoomDjUserDataBackup
import io.sonora.core.djstore.RoomDjAnalysisStore
import io.sonora.core.djstore.db.DjStoreDatabase
import io.sonora.core.domain.port.DjBackupStore
import javax.inject.Singleton

/** The permanent DJ store: one database for the whole process, and the ports built on it. */
@Module
@InstallIn(SingletonComponent::class)
object DjStoreModule {

    /** The one database instance. It is user data, so it is only ever opened through [DjStoreDatabase.builder]. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DjStoreDatabase =
        DjStoreDatabase.builder(context).build()

    @Provides
    @Singleton
    fun provideAnalysisStore(database: DjStoreDatabase): DjAnalysisStore = RoomDjAnalysisStore(database.analysisDao())

    /** The DJ store's share of the app backup (analyses, grid edits and cues); `:core:data` binds it as an optional port. */
    @Provides
    @Singleton
    fun provideBackupStore(database: DjStoreDatabase, store: DjAnalysisStore): DjBackupStore =
        RoomDjBackupStore(database.analysisDao(), store, RoomDjUserDataBackup(database.gridEditDao(), database.cueDao()))

    @Provides
    @Singleton
    fun provideAudioIdIndex(database: DjStoreDatabase): AudioIdIndex = RoomAudioIdIndex(database.audioIdDao())

    /** The user's beat grid edits (D3), kept beside the analyses and keyed by the same audio identity. */
    @Provides
    @Singleton
    fun provideGridEditStore(database: DjStoreDatabase): DjGridEditStore = RoomDjGridEditStore(database.gridEditDao())

    /** The user's cues (D4): hot cues, memory cues and saved loops, keyed by the same audio identity. */
    @Provides
    @Singleton
    fun provideCueStore(database: DjStoreDatabase): DjCueStore = RoomDjCueStore(database.cueDao())
}
