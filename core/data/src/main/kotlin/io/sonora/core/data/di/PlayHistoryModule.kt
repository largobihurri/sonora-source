package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.PlayCountDao
import io.sonora.core.data.library.plays.RoomPlayHistory
import io.sonora.core.domain.library.plays.PlayHistoryRepository

/** Play counts, recorded by playback and read by the library (the artists' artwork order). */
@Module
@InstallIn(SingletonComponent::class)
interface PlayHistoryBindings {
    @Binds
    fun bindPlayHistory(impl: RoomPlayHistory): PlayHistoryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object PlayHistoryModule {
    @Provides
    fun providePlayCountDao(database: LibraryDatabase): PlayCountDao = database.playCountDao()
}
