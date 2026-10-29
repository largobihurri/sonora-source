package io.sonora.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.SafLibraryFolderRepository
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.saf.LibraryWalker
import io.sonora.core.data.library.saf.SafTreeWalker
import io.sonora.core.data.library.scan.LibraryScanCoordinator
import io.sonora.core.data.library.scan.ScanClock
import io.sonora.core.data.library.tags.JAudioTaggerTagReader
import io.sonora.core.data.library.tags.TagReader
import io.sonora.core.domain.library.removal.ConverterFolderAccess
import io.sonora.core.domain.library.removal.FolderAccessUsers
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryScanner
import javax.inject.Singleton

/** The scanner's Room index, which [DataModule] serves as the library. */
@Module
@InstallIn(SingletonComponent::class)
object LibraryIndexModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME).build()

    @Provides
    fun provideLibraryDao(database: LibraryDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideScanClock(): ScanClock = ScanClock.System

    /** Whose access a removed library folder's grant may still be (the converter's). */
    @Provides
    fun provideFolderAccessUsers(outputRoot: ConversionOutputRootStore, batches: ConversionBatchRepository): FolderAccessUsers =
        ConverterFolderAccess(outputRoot, batches)
}

@Module
@InstallIn(SingletonComponent::class)
interface LibraryIndexBindings {
    @Binds
    fun bindLibraryScanner(impl: LibraryScanCoordinator): LibraryScanner

    @Binds
    fun bindLibraryWalker(impl: SafTreeWalker): LibraryWalker

    @Binds
    fun bindTagReader(impl: JAudioTaggerTagReader): TagReader

    @Binds
    fun bindLibraryFolderRepository(impl: SafLibraryFolderRepository): LibraryFolderRepository
}
