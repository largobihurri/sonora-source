package io.sonora.core.djanalysis.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.djanalysis.AnalysisThrottle
import io.sonora.core.djanalysis.AndroidDjAnalysisLog
import io.sonora.core.djanalysis.AndroidDjTrackAnalyzer
import io.sonora.core.djanalysis.AudioDocuments
import io.sonora.core.djanalysis.CurrentDjAnalyzer
import io.sonora.core.djanalysis.DeviceLoadSource
import io.sonora.core.djanalysis.DjAnalysisCoordinator
import io.sonora.core.djanalysis.DjAnalysisLimitsHolder
import io.sonora.core.djanalysis.DjAnalysisRunner
import io.sonora.core.djanalysis.PowerManagerDeviceLoad
import io.sonora.core.djanalysis.SafAudioDocuments
import io.sonora.core.djanalysis.SafAudioIdentifier
import io.sonora.core.djanalysis.SharedPreferencesDjModeSettings
import io.sonora.core.djanalysis.SharedPreferencesDjQuantizeSettings
import io.sonora.core.djanalysis.StoredDjTrackAnalyses
import io.sonora.core.djanalysis.StoredDjTrackCues
import io.sonora.core.djanalysis.StoredDjTrackGridEditor
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.AudioIdentifier
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import io.sonora.core.djmodel.DjAnalysisControl
import io.sonora.core.djmodel.DjAnalysisPauseSource
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djmodel.DjAnalyzerInfo
import io.sonora.core.djmodel.DjCueStore
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.DjTrackAnalyzer
import io.sonora.core.djmodel.DjTrackCues
import io.sonora.core.djmodel.DjTrackGridEditor
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.dubmodel.DubModeSettings
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/** The one thread the DJ analysis (downmix, DSP sinks, the beat model's calling thread) runs on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DjAnalysisDispatcher

/** The process-wide scope of the DJ analysis scheduler. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DjAnalysisScope

/**
 * The Android side of the DJ analysis. Its other inputs come from their own modules: [PcmDecoder] (`:core:decoder`),
 * [AnalysisEngineFactory] (`:core:analysis`, qm-dsp), [BeatActivationModelFactory] (`:core:beatmodel`, ONNX Runtime),
 * [DjAnalysisStore] and [AudioIdIndex] (`:core:djstore`) and the player (`:core:playback`).
 */
@Module
@InstallIn(SingletonComponent::class)
object DjAnalysisModule {

    /**
     * The DJ layout is exclusive with the DUB layout: turning DJ on turns DUB off once DJ is on (DUB mode's own
     * binding, in `:core:data`, does the reverse), so a switch never reads both off. [Lazy] breaks the cycle between
     * the two flags.
     */
    @Provides
    @Singleton
    fun provideDjModeSettings(@ApplicationContext context: Context, dub: Lazy<DubModeSettings>): DjModeSettings =
        SharedPreferencesDjModeSettings(
            context.getSharedPreferences(SharedPreferencesDjModeSettings.FILE_NAME, Context.MODE_PRIVATE),
            turnOtherLayoutOff = { dub.get().setEnabled(false) },
        )

    /** The Quantize toggle of the DJ screen (D4), in the DJ mode preference file; on by default. */
    @Provides
    @Singleton
    fun provideDjQuantizeSettings(@ApplicationContext context: Context): DjQuantizeSettings = SharedPreferencesDjQuantizeSettings(
        context.getSharedPreferences(SharedPreferencesDjModeSettings.FILE_NAME, Context.MODE_PRIVATE),
    )

    /** The coordinator is the one source of the analysis status; screens inject the port, not the class. */
    @Provides
    fun provideDjAnalysisStatusSource(coordinator: DjAnalysisCoordinator): DjAnalysisStatusSource = coordinator

    /** The same coordinator takes the DJ screen's retry and re-analyse requests (D2.11). */
    @Provides
    fun provideDjAnalysisControl(coordinator: DjAnalysisCoordinator): DjAnalysisControl = coordinator

    @Provides
    fun provideDjAnalyzerInfo(): DjAnalyzerInfo = CurrentDjAnalyzer

    @Provides
    fun provideDeviceLoadSource(@ApplicationContext context: Context): DeviceLoadSource = PowerManagerDeviceLoad(context)

    /** The one holder of the limits in force: the coordinator writes them, the analyser and the beat model read them. */
    @Provides
    @Singleton
    fun provideLimitsHolder(): DjAnalysisLimitsHolder = DjAnalysisLimitsHolder()

    @Provides
    fun provideBudgetSource(holder: DjAnalysisLimitsHolder): DjAnalysisBudgetSource = holder

    /** Whether the analysis waits for the phone to cool down, for the DJ screen (D2.11). */
    @Provides
    fun providePauseSource(holder: DjAnalysisLimitsHolder): DjAnalysisPauseSource = holder

    /**
     * One background-priority thread: analyses run one at a time anyway (the scheduler has a single worker), and the
     * low priority keeps the audio and the UI ahead of it; each track then sets the priority of the performance in
     * force ([AnalysisThrottle]). The beat model's own intra-op threads come from the same limits.
     */
    @Provides
    @Singleton
    @DjAnalysisDispatcher
    fun provideAnalysisDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @Provides
    @Singleton
    @DjAnalysisScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideAudioDocuments(@ApplicationContext context: Context): AudioDocuments = SafAudioDocuments(context.contentResolver)

    @Provides
    @Singleton
    fun provideIdentifier(documents: AudioDocuments, index: AudioIdIndex): SafAudioIdentifier =
        SafAudioIdentifier(documents, index)

    /**
     * The playing track's stored analysis for the DJ screens, with the user's grid edit joined in: reads only, never
     * hashes (the scheduler does).
     */
    @Provides
    @Singleton
    fun provideDjTrackAnalyses(
        status: DjAnalysisStatusSource,
        documents: AudioDocuments,
        index: AudioIdIndex,
        store: DjAnalysisStore,
        edits: DjGridEditStore,
    ): DjTrackAnalyses = StoredDjTrackAnalyses(status, documents, index, store, edits, Dispatchers.IO)

    /** Where the grid editor writes: the edit of the track at a uri, under its remembered audio id. */
    @Provides
    @Singleton
    fun provideDjTrackGridEditor(
        documents: AudioDocuments,
        index: AudioIdIndex,
        edits: DjGridEditStore,
    ): DjTrackGridEditor = StoredDjTrackGridEditor(documents, index, edits, Dispatchers.IO)

    /** The cues of the playing track for the DJ screen (D4): read and written under its remembered audio id. */
    @Provides
    @Singleton
    fun provideDjTrackCues(
        status: DjAnalysisStatusSource,
        documents: AudioDocuments,
        index: AudioIdIndex,
        cues: DjCueStore,
    ): DjTrackCues = StoredDjTrackCues(status, documents, index, cues, Dispatchers.IO)

    @Provides
    fun provideAudioIdentifier(identifier: SafAudioIdentifier): AudioIdentifier = identifier

    @Provides
    @Singleton
    fun provideAnalyzer(
        decoder: PcmDecoder,
        models: BeatActivationModelFactory,
        engines: AnalysisEngineFactory,
        @DjAnalysisDispatcher dispatcher: CoroutineDispatcher,
        limits: DjAnalysisBudgetSource,
    ): AndroidDjTrackAnalyzer = AndroidDjTrackAnalyzer(
        decoder, models, engines, AndroidDjAnalysisLog, dispatcher,
        throttle = AnalysisThrottle(limits, setPriority = { nice -> runCatching { Process.setThreadPriority(nice) } }),
    )

    @Provides
    fun provideTrackAnalyzer(analyzer: AndroidDjTrackAnalyzer): DjTrackAnalyzer = analyzer

    @Provides
    @Singleton
    fun provideRunner(
        @ApplicationContext context: Context,
        identifier: SafAudioIdentifier,
        store: DjAnalysisStore,
        analyzer: AndroidDjTrackAnalyzer,
    ): DjAnalysisRunner = DjAnalysisRunner(
        identifier = { uri -> identifier.identify(uri) },
        store = store,
        passes = analyzer,
        log = AndroidDjAnalysisLog,
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    private const val THREAD_NAME = "SonoraDjAnalysis"
}
