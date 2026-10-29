package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.conversion.DataStoreConversionOutputRoot
import io.sonora.core.data.conversion.RoomConversionBatchRepository
import io.sonora.core.data.conversion.SafConvertedFileReader
import io.sonora.core.data.conversion.db.ConversionHistoryDao
import io.sonora.core.data.conversion.saf.SafConversionFiles
import io.sonora.core.data.conversion.saf.SafConversionSourceExpander
import io.sonora.core.data.conversion.saf.SafSourceFolderAccess
import io.sonora.core.data.conversion.work.WorkManagerConversionScheduler
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.conversion.batch.CancelConversionBatch
import io.sonora.core.domain.conversion.batch.ConversionBatchRunner
import io.sonora.core.domain.conversion.batch.ObserveConversionHistory
import io.sonora.core.domain.conversion.batch.OriginalReplacer
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.ReplaceBatchOriginals
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.conversion.batch.StartConversionBatch
import io.sonora.core.domain.library.ObserveConvertedMusic
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBatchScheduler
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.ConvertedFileReader
import io.sonora.core.domain.port.SourceFolderAccess
import java.time.Clock
import javax.inject.Singleton

/**
 * The converter's batches: history in Room, documents through SAF, jobs in WorkManager, and the domain's batch use
 * cases built on them. [AudioConverter] and [ConversionBudgetSource] come from core:transcoder.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ConversionBindings {
    @Binds
    fun bindBatchRepository(impl: RoomConversionBatchRepository): ConversionBatchRepository

    @Binds
    fun bindConversionFiles(impl: SafConversionFiles): ConversionFiles

    @Binds
    fun bindOutputRootStore(impl: DataStoreConversionOutputRoot): ConversionOutputRootStore

    @Binds
    fun bindSourceFolderAccess(impl: SafSourceFolderAccess): SourceFolderAccess

    @Binds
    fun bindSourceExpander(impl: SafConversionSourceExpander): ConversionSourceExpander

    @Binds
    fun bindBatchScheduler(impl: WorkManagerConversionScheduler): ConversionBatchScheduler

    @Binds
    fun bindConvertedFileReader(impl: SafConvertedFileReader): ConvertedFileReader
}

@Module
@InstallIn(SingletonComponent::class)
object ConversionModule {
    @Provides
    fun provideConversionHistoryDao(database: LibraryDatabase): ConversionHistoryDao = database.conversionHistoryDao()

    /** The system clock in the current default zone (read per injection, so a zone change applies to new users). */
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun provideOriginalReplacer(
        files: ConversionFiles,
        access: SourceFolderAccess,
        converter: AudioConverter,
        repository: ConversionBatchRepository,
    ) = OriginalReplacer(files, access, converter, repository)

    @Provides
    fun provideBatchRunner(
        repository: ConversionBatchRepository,
        files: ConversionFiles,
        outputRoot: ConversionOutputRootStore,
        converter: AudioConverter,
        budgets: ConversionBudgetSource,
        replacer: OriginalReplacer,
    ) = ConversionBatchRunner(repository, files, outputRoot, converter, budgets, replacer)

    @Provides
    fun provideReplaceBatchOriginals(repository: ConversionBatchRepository, replacer: OriginalReplacer) =
        ReplaceBatchOriginals(repository, replacer)

    @Provides
    fun provideStartConversionBatch(
        expander: ConversionSourceExpander,
        repository: ConversionBatchRepository,
        outputRoot: ConversionOutputRootStore,
        access: SourceFolderAccess,
        scheduler: ConversionBatchScheduler,
        clock: Clock,
    ) = StartConversionBatch(expander, repository, outputRoot, access, scheduler, clock)

    @Provides
    fun provideCancelConversionBatch(scheduler: ConversionBatchScheduler, runner: ConversionBatchRunner) =
        CancelConversionBatch(scheduler, runner)

    @Provides
    fun provideObserveConversionHistory(repository: ConversionBatchRepository, clock: Clock) =
        ObserveConversionHistory(repository, clock)

    @Provides
    fun provideResolveConvertedTracks(repository: ConversionBatchRepository, reader: ConvertedFileReader) =
        ResolveConvertedTracks(repository, reader)

    /** One list of sources for the next batch in the whole process: the Library's "Convert…" fills the Converter's. */
    @Provides
    @Singleton
    fun providePendingConversionSources() = PendingConversionSources()

    @Provides
    fun provideObserveConvertedMusic(repository: ConversionBatchRepository, resolve: ResolveConvertedTracks, clock: Clock) =
        ObserveConvertedMusic(repository, resolve, clock)
}
