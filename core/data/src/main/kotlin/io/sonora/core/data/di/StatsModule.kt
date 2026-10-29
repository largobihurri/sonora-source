package io.sonora.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.stats.ListeningDao
import io.sonora.core.data.stats.RoomListeningHistory
import io.sonora.core.data.stats.StatsDatabase
import io.sonora.core.domain.stats.ListeningHistory
import javax.inject.Singleton

/** Listening statistics: recorded per playback by playback, read by Library > Statistics. */
@Module
@InstallIn(SingletonComponent::class)
interface StatsBindings {
    @Binds
    fun bindListeningHistory(impl: RoomListeningHistory): ListeningHistory
}

@Module
@InstallIn(SingletonComponent::class)
object StatsModule {
    @Provides
    @Singleton
    fun provideStatsDatabase(@ApplicationContext context: Context): StatsDatabase =
        Room.databaseBuilder(context, StatsDatabase::class.java, StatsDatabase.NAME).build()

    @Provides
    fun provideListeningDao(database: StatsDatabase): ListeningDao = database.listeningDao()
}
