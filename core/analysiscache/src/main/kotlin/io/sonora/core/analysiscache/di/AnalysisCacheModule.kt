package io.sonora.core.analysiscache.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.analysis.AndroidTrackAnalyzer
import io.sonora.core.analysiscache.RoomAnalysisCache
import io.sonora.core.analysiscache.db.AnalysisCacheDatabase
import io.sonora.core.domain.analysis.AnalysisCache
import io.sonora.core.domain.analysis.CachedTrackAnalyzer
import io.sonora.core.domain.analysis.TrackAnalyzer
import javax.inject.Singleton

/** The persistent analysis cache and the cached analyser built on it. */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisCacheModule {
    private const val TAG = "SonoraAnalysisCache"

    /** Only recomputable data: a schema change (either way) drops the cache instead of migrating it. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AnalysisCacheDatabase =
        Room.databaseBuilder(context, AnalysisCacheDatabase::class.java, AnalysisCacheDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideAnalysisCache(database: AnalysisCacheDatabase): AnalysisCache =
        RoomAnalysisCache(database.analysisCacheDao())

    /** Keyed by the version the app's analyser stamps on its results; a new version re-analyses (and prunes). */
    @Provides
    @Singleton
    fun provideCachedTrackAnalyzer(analyzer: TrackAnalyzer, cache: AnalysisCache): CachedTrackAnalyzer =
        CachedTrackAnalyzer(analyzer, cache, AndroidTrackAnalyzer.ANALYZER_VERSION) { failure ->
            Log.w(TAG, "Analysis cache failed; analysing without it", failure)
        }
}
