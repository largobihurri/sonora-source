package io.sonora.core.beatmodel.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.beatmodel.AssetBeatModelSource
import io.sonora.core.beatmodel.BeatThisModel
import io.sonora.core.beatmodel.OrtBeatModelFactory
import io.sonora.core.beatmodel.ThreadPriorityScope
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import javax.inject.Singleton

/**
 * The beat model port, backed by the bundled Beat This! model on ONNX Runtime (CPU). One factory per process: it verifies
 * the asset once. The session's threads and priority follow the DJ analysis performance ([DjAnalysisBudgetSource], bound
 * by `:core:djanalysis`).
 */
@Module
@InstallIn(SingletonComponent::class)
object BeatModelModule {

    @Provides
    @Singleton
    fun provideBeatModelFactory(@ApplicationContext context: Context, limits: DjAnalysisBudgetSource): BeatActivationModelFactory =
        OrtBeatModelFactory(
            AssetBeatModelSource(context.assets, BeatThisModel.ASSET_PATH),
            limits = limits,
            priority = ThreadPriorityScope.android(),
        )
}
