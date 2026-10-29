package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.duplicates.RoomDuplicateDismissals
import io.sonora.core.data.library.duplicates.RoomDuplicateFingerprints
import io.sonora.core.data.library.duplicates.RoomDuplicateLibrary
import io.sonora.core.data.library.duplicates.RoomDuplicateSettings
import io.sonora.core.data.library.duplicates.SafContentHasher
import io.sonora.core.data.library.duplicates.WorkManagerDuplicateWork
import io.sonora.core.data.library.duplicates.db.DuplicateDao
import io.sonora.core.domain.library.duplicates.CheckDuplicates
import io.sonora.core.domain.library.duplicates.ContentHasher
import io.sonora.core.domain.library.duplicates.DeleteDuplicateCopies
import io.sonora.core.domain.library.duplicates.DuplicateAlerts
import io.sonora.core.domain.library.duplicates.DuplicateDismissals
import io.sonora.core.domain.library.duplicates.DuplicateFingerprints
import io.sonora.core.domain.library.duplicates.DuplicateFirstRun
import io.sonora.core.domain.library.duplicates.DuplicateLibrary
import io.sonora.core.domain.library.duplicates.DuplicateNotifier
import io.sonora.core.domain.library.duplicates.DuplicateScan
import io.sonora.core.domain.library.duplicates.DuplicateWork
import io.sonora.core.domain.library.duplicates.FindDuplicates
import io.sonora.core.domain.library.duplicates.ReviewDuplicateBacklog
import io.sonora.core.domain.library.removal.DeleteFromPhone
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The duplicate finder: its tables in the library index, SAF hashing and the background checks on WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
interface DuplicateBindings {
    @Binds
    fun bindLibrary(impl: RoomDuplicateLibrary): DuplicateLibrary

    @Binds
    fun bindDismissals(impl: RoomDuplicateDismissals): DuplicateDismissals

    @Binds
    fun bindFingerprints(impl: RoomDuplicateFingerprints): DuplicateFingerprints

    @Binds
    fun bindAlerts(impl: RoomDuplicateSettings): DuplicateAlerts

    @Binds
    fun bindFirstRun(impl: RoomDuplicateSettings): DuplicateFirstRun

    @Binds
    fun bindHasher(impl: SafContentHasher): ContentHasher

    @Binds
    fun bindWork(impl: WorkManagerDuplicateWork): DuplicateWork
}

/** The pure use cases, built here; [DuplicateScan] is one per process, so every screen and job shares its result. */
@Module
@InstallIn(SingletonComponent::class)
object DuplicatesModule {
    @Provides
    fun provideDuplicateDao(database: LibraryDatabase): DuplicateDao = database.duplicateDao()

    @Provides
    fun provideDeleteDuplicateCopies(deleteFromPhone: DeleteFromPhone) = DeleteDuplicateCopies(deleteFromPhone)

    @Provides
    @Singleton
    fun provideDuplicateScan(
        library: DuplicateLibrary,
        hasher: ContentHasher,
        fingerprints: DuplicateFingerprints,
        dismissals: DuplicateDismissals,
        delete: DeleteDuplicateCopies,
    ): DuplicateScan = DuplicateScan(
        FindDuplicates(library, hasher, fingerprints, dismissals),
        library,
        dismissals,
        delete,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Provides
    fun provideCheckDuplicates(scan: DuplicateScan, alerts: DuplicateAlerts, notifier: DuplicateNotifier) =
        CheckDuplicates(scan, alerts, notifier)

    @Provides
    fun provideReviewDuplicateBacklog(
        library: DuplicateLibrary,
        fingerprints: DuplicateFingerprints,
        firstRun: DuplicateFirstRun,
        work: DuplicateWork,
        scan: DuplicateScan,
    ) = ReviewDuplicateBacklog(library, fingerprints, firstRun, work, scan)
}
