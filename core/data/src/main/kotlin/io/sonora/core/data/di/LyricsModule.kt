package io.sonora.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.lyrics.SafLocalLyricsSource
import io.sonora.core.data.lyrics.db.LyricsDao
import io.sonora.core.data.lyrics.db.LyricsDatabase
import io.sonora.core.data.lyrics.db.RoomLyricsCache
import io.sonora.core.domain.lyrics.LyricsRepository
import io.sonora.core.domain.port.LocalLyricsSource
import io.sonora.core.domain.port.LyricsCache
import io.sonora.core.domain.port.OnlineLyricsSource
import java.util.Optional
import javax.inject.Singleton

/** Lyrics sources: the song's own files and tags, an online service on request (`full` only), and the downloads kept in their own database. */
@Module
@InstallIn(SingletonComponent::class)
interface LyricsBindings {
    @Binds
    fun bindLocalLyricsSource(impl: SafLocalLyricsSource): LocalLyricsSource

    @Binds
    fun bindLyricsCache(impl: RoomLyricsCache): LyricsCache
}

@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {
    /** Downloaded lyrics can always be searched again, so a schema change simply drops them. */
    @Provides
    @Singleton
    fun provideLyricsDatabase(@ApplicationContext context: Context): LyricsDatabase =
        Room.databaseBuilder(context, LyricsDatabase::class.java, LyricsDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideLyricsDao(database: LyricsDatabase): LyricsDao = database.lyricsDao()

    @Provides
    fun provideLyricsRepository(local: LocalLyricsSource, cache: LyricsCache, online: Optional<OnlineLyricsSource>) =
        LyricsRepository(local, cache, online.orElse(null))
}

/** The online lyrics service is optional: only the `full` build binds one ([io.sonora.core.data.lyrics.lrclib.LrclibSource]); the Play build has none. */
@Module
@InstallIn(SingletonComponent::class)
interface OptionalLyricsModule {
    @BindsOptionalOf
    fun optionalOnlineLyricsSource(): OnlineLyricsSource
}
