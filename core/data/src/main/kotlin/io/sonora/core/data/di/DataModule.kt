package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.conversion.JAudioTaggerSourceMetadataReader
import io.sonora.core.data.export.LibraryExportArtwork
import io.sonora.core.data.library.RoomFolderBrowser
import io.sonora.core.data.library.RoomLibraryRepository
import io.sonora.core.data.library.edit.AndroidPictureLoader
import io.sonora.core.data.library.edit.SafSongTagEditor
import io.sonora.core.data.library.info.JAudioTaggerFileInfoReader
import io.sonora.core.data.playback.FilePlaybackSnapshotStore
import io.sonora.core.domain.export.ExportArtwork
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PictureLoader
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.domain.port.TrackFileInfoReader

/** Binds data-layer adapters to domain ports. The library is served from the scanner's Room index. */
@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds
    fun bindLibraryRepository(impl: RoomLibraryRepository): LibraryRepository

    @Binds
    fun bindFolderBrowser(impl: RoomFolderBrowser): FolderBrowser

    @Binds
    fun bindTrackFileInfoReader(impl: JAudioTaggerFileInfoReader): TrackFileInfoReader

    @Binds
    fun bindPlaybackSnapshotStore(impl: FilePlaybackSnapshotStore): PlaybackSnapshotStore

    @Binds
    fun bindSourceMetadataReader(impl: JAudioTaggerSourceMetadataReader): SourceMetadataReader

    @Binds
    fun bindSongTagEditor(impl: SafSongTagEditor): SongTagEditor

    @Binds
    fun bindPictureLoader(impl: AndroidPictureLoader): PictureLoader

    @Binds
    fun bindExportArtwork(impl: LibraryExportArtwork): ExportArtwork
}
