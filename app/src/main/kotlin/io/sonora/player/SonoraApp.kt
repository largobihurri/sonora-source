package io.sonora.player

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import dagger.hilt.android.HiltAndroidApp
import io.sonora.core.data.library.duplicates.DuplicateTriggers
import io.sonora.core.data.library.scan.LibraryScanCoordinator
import io.sonora.core.djanalysis.DjAnalysisCoordinator
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.kora.SonoraKora
import io.sonora.player.distribution.Distribution
import io.sonora.player.locale.LanguageSync
import io.sonora.player.locale.LegacyLocale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class SonoraApp : Application(), WorkConfiguration.Provider {
    @Inject lateinit var libraryScanCoordinator: LibraryScanCoordinator

    @Inject lateinit var duplicateTriggers: DuplicateTriggers

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var languageSync: LanguageSync

    @Inject lateinit var preferences: PreferencesRepository

    @Inject lateinit var djAnalysis: DjAnalysisCoordinator

    @Inject lateinit var kora: SonoraKora

    /** Process-wide work that follows the main thread: the language sync. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** WorkManager initializes on first use with Hilt-injected workers (the converter's batch job). */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Without per-app languages the first activity wraps its context in the stored language, so it is read
            // (a few milliseconds, once per process) before any activity exists.
            LegacyLocale.set(runBlocking { preferences.preferences.first().language })
            LegacyLocale.applyTo(this)
        }
        // Applies the stored language (Castellano until the user picks another) now and whenever it changes.
        appScope.launch { languageSync.run() }
        // Scans the library folders now (if any) and whenever they change, for the lifetime of the process.
        libraryScanCoordinator.start()
        // Schedules the daily duplicate check and reviews the backlog after each library scan (T18).
        duplicateTriggers.start()
        // Analyses the playing track and the next ones while DJ mode is on (off by default: then it does nothing).
        djAnalysis.start()
        // What the build adds at start: the `full` build loads the Cast context before any Cast button or player needs it.
        Distribution.onCreate(this)
        // Publishes this app's Kora node (media.library, media.playback) once it is complete; its ports are built lazily.
        kora.start(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android resets the application resources to the device language on every configuration change.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) LegacyLocale.applyTo(this)
    }
}
