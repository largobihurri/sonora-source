package io.sonora.core.analysis.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.analysis.AndroidTrackAnalyzer
import io.sonora.core.analysis.NativeAnalyzer
import io.sonora.core.domain.analysis.TrackAnalyzer
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher track analysis (downmix, native DSP) runs on; decoding runs on the decoder's own dispatcher. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalysisDispatcher

@Module
@InstallIn(SingletonComponent::class)
interface AnalysisModule {
    @Binds
    fun bindTrackAnalyzer(impl: AndroidTrackAnalyzer): TrackAnalyzer

    companion object {
        @Provides
        fun provideAnalysisEngineFactory(): AnalysisEngineFactory = AnalysisEngineFactory(NativeAnalyzer::create)

        @Provides
        @AnalysisDispatcher
        fun provideAnalysisDispatcher(): CoroutineDispatcher = Dispatchers.Default
    }
}
