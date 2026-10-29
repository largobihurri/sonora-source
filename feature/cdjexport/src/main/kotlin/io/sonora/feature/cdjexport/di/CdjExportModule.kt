package io.sonora.feature.cdjexport.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.export.ExportSourceReader
import io.sonora.core.domain.export.RemovableVolumes
import io.sonora.core.domain.export.StickLocator
import io.sonora.feature.cdjexport.AppEnvironment
import io.sonora.feature.cdjexport.export.AndroidRemovableVolumes
import io.sonora.feature.cdjexport.export.DjExportSource
import io.sonora.feature.cdjexport.export.ExportCoordinator
import io.sonora.feature.cdjexport.export.ExportForeground
import io.sonora.feature.cdjexport.export.ExportTrackConverter
import io.sonora.feature.cdjexport.export.ExportRunner
import io.sonora.feature.cdjexport.export.ResolverUriPermissions
import io.sonora.feature.cdjexport.export.SafExportSources
import io.sonora.feature.cdjexport.export.SafStickLocator
import io.sonora.feature.cdjexport.export.ServiceExportForeground
import io.sonora.feature.cdjexport.export.StoredDjExportSource
import io.sonora.feature.cdjexport.export.TranscoderExportConverter
import io.sonora.feature.cdjexport.export.UriPermissions
import java.time.Clock
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher the export screens run blocking USB work on (opening, formatting, closing a stick). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CdjExportIoDispatcher

/** The clock the export dates its tracks and playlists with. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CdjExportClock

@Module
@InstallIn(SingletonComponent::class)
object CdjExportModule {
    @Provides
    @CdjExportIoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Today's date for the export (analysis date, date added, default playlist name). */
    @Provides
    @CdjExportClock
    fun provideClock(): Clock = Clock.systemDefaultZone()

    /** The app and phone facts that "Copy technical details" reports. */
    @Provides
    fun provideAppEnvironment(@ApplicationContext context: Context): AppEnvironment = AppEnvironment(
        appVersion = appVersion(context),
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    private fun appVersion(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName} (${info.longVersionCode})"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }
}

/** The export's ports and their Android adapters. */
@Module
@InstallIn(SingletonComponent::class)
interface CdjExportBindings {
    @Binds
    fun bindExportSources(impl: SafExportSources): ExportSourceReader

    @Binds
    fun bindStickLocator(impl: SafStickLocator): StickLocator

    @Binds
    fun bindRemovableVolumes(impl: AndroidRemovableVolumes): RemovableVolumes

    @Binds
    fun bindExportRunner(impl: ExportCoordinator): ExportRunner

    @Binds
    fun bindExportForeground(impl: ServiceExportForeground): ExportForeground

    @Binds
    fun bindUriPermissions(impl: ResolverUriPermissions): UriPermissions

    @Binds
    fun bindDjExportSource(impl: StoredDjExportSource): DjExportSource

    @Binds
    fun bindExportTrackConverter(impl: TranscoderExportConverter): ExportTrackConverter
}
