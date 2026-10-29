package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.archive.AndroidArchiveFiles
import io.sonora.core.data.archive.RoomArchiveImportRepository
import io.sonora.core.data.archive.RoomIndexedSongUris
import io.sonora.core.data.archive.SafImportedFolderDeleter
import io.sonora.core.data.archive.SafImportedFolders
import io.sonora.core.data.archive.TagReaderExtractedSongTags
import io.sonora.core.data.archive.ZipArchiveCompressor
import io.sonora.core.data.archive.ZipArchiveImporter
import io.sonora.core.data.archive.db.ArchiveImportDao
import io.sonora.core.data.archive.work.WorkManagerArchiveScheduler
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.archive.ArchiveCompressor
import io.sonora.core.domain.archive.ArchiveFiles
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.DeleteArchiveImport
import io.sonora.core.domain.archive.ExtractedSongTags
import io.sonora.core.domain.archive.FinishArchiveImport
import io.sonora.core.domain.archive.ForgetArchiveImport
import io.sonora.core.domain.archive.ImportDeleter
import io.sonora.core.domain.archive.ImportFinisher
import io.sonora.core.domain.archive.ImportRecorder
import io.sonora.core.domain.archive.ImportedFolderDeleter
import io.sonora.core.domain.archive.ImportedFolders
import io.sonora.core.domain.archive.IndexedSongUris
import io.sonora.core.domain.archive.RecordArchiveImport
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.port.ArchiveImportRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import java.time.Clock

/**
 * "Compress" and archive import: ZIP (zip4j) over the songs' documents and shared-storage folders, run as WorkManager
 * jobs with passwords kept in memory only.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ArchiveBindings {
    @Binds
    fun bindCompressor(impl: ZipArchiveCompressor): ArchiveCompressor

    @Binds
    fun bindImporter(impl: ZipArchiveImporter): ArchiveImporter

    @Binds
    fun bindJobScheduler(impl: WorkManagerArchiveScheduler): ArchiveJobScheduler

    @Binds
    fun bindFiles(impl: AndroidArchiveFiles): ArchiveFiles

    @Binds
    fun bindImportedFolders(impl: SafImportedFolders): ImportedFolders

    @Binds
    fun bindImportRepository(impl: RoomArchiveImportRepository): ArchiveImportRepository

    @Binds
    fun bindExtractedSongTags(impl: TagReaderExtractedSongTags): ExtractedSongTags

    @Binds
    fun bindImportedFolderDeleter(impl: SafImportedFolderDeleter): ImportedFolderDeleter

    @Binds
    fun bindIndexedSongUris(impl: RoomIndexedSongUris): IndexedSongUris
}

/** The end of an import (library folder, rescan, the archive's playlist) and its history, pure use cases built here. */
@Module
@InstallIn(SingletonComponent::class)
object ArchiveImportModule {
    @Provides
    fun provideImportFinisher(
        folders: LibraryFolderRepository,
        imported: ImportedFolders,
        library: LibraryRepository,
        playlists: PlaylistRepository,
    ): ImportFinisher = FinishArchiveImport(folders, imported, library, playlists)

    @Provides
    fun provideArchiveImportDao(database: LibraryDatabase): ArchiveImportDao = database.archiveImportDao()

    @Provides
    fun provideImportRecorder(
        imports: ArchiveImportRepository,
        imported: ImportedFolders,
        tags: ExtractedSongTags,
        clock: Clock,
    ): ImportRecorder = RecordArchiveImport(imports, imported, tags, clock)

    @Provides
    fun provideForgetArchiveImport(imports: ArchiveImportRepository) = ForgetArchiveImport(imports)

    @Provides
    fun provideDeleteArchiveImport(
        imports: ArchiveImportRepository,
        indexed: IndexedSongUris,
        deleteFromPhone: DeleteFromPhone,
        folders: LibraryFolderRepository,
        deleter: ImportedFolderDeleter,
        playlists: PlaylistRepository,
    ): ImportDeleter = DeleteArchiveImport(imports, indexed, deleteFromPhone, folders, deleter, playlists)
}
