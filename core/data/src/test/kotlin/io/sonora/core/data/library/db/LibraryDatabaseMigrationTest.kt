package io.sonora.core.data.library.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.archive.db.ArchiveImportEntity
import io.sonora.core.data.archive.db.ArchiveImportSongEntity
import io.sonora.core.data.conversion.db.ConversionBatchEntity
import io.sonora.core.data.conversion.db.ConversionBatchItemEntity
import io.sonora.core.data.library.duplicates.db.DuplicateSettingsEntity
import io.sonora.core.data.playlist.db.PlaylistEntity
import io.sonora.core.data.playlist.db.PlaylistEntryEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryDatabaseMigrationTest {

    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.deleteDatabase(LibraryDatabase.NAME)
    }

    /** Creates the database exactly as the exported schema [version] describes it. */
    private fun createFromSchema(version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = JSONObject(File("schemas/${LibraryDatabase::class.qualifiedName}/$version.json").readText())
            .getJSONObject("database")
        val file = context.getDatabasePath(LibraryDatabase.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.fill()
            db.version = version
        }
    }

    @Test
    fun `a version 1 index opens as version 2 with its folders and an empty failure table`() {
        createFromSchema(1) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.libraryDao()
            runBlocking {
                assertThat(dao.folderList()).containsExactly(LibraryFolderEntity("content://tree/music", "Music", 1))
                assertThat(dao.readFailures("content://tree/music")).isEmpty()
                dao.upsertReadFailures(listOf(TagReadFailureEntity("content://tree/music/document/a", "content://tree/music", 1)))
                assertThat(dao.readFailures("content://tree/music").single().failures).isEqualTo(1)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 2 index opens as version 3 keeping its tracks and failure counts`() {
        createFromSchema(2) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tag_read_failures (document_uri, folder_tree_uri, failures) " +
                    "VALUES ('content://tree/music/document/a', 'content://tree/music', 2)",
            )
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation) VALUES (7, 'content://tree/music/document/b', " +
                    "'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, 30, 0, 1000, 'B', 'Band', 1, " +
                    "'Record', 2, 'Band', 1, 0)",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.libraryDao()
            runBlocking {
                val failure = dao.readFailures("content://tree/music").single()
                assertThat(failure.failures).isEqualTo(2)
                assertThat(failure.lastFailureMs).isEqualTo(0L)
                assertThat(dao.tracksUnderFolder("content://tree/music", "Music").map { it.id }).containsExactly(7L)
            }
            val indices = db.openHelper.readableDatabase.query("PRAGMA index_list(tracks)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertThat(indices).contains("index_tracks_folder_tree_uri_folder_path")
            assertThat(indices).doesNotContain("index_tracks_folder_tree_uri")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 3 index opens as version 4 keeping its library and with an empty converter history`() {
        createFromSchema(3) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().folderList()).containsExactly(LibraryFolderEntity("content://tree/music", "Music", 1))
                val history = db.conversionHistoryDao()
                assertThat(history.batches().first()).isEmpty()
                val batch = ConversionBatchEntity(
                    profileId = "xdj-700",
                    performanceMode = "NORMAL",
                    createdAtMs = 5,
                    outputFolderName = "2026-10-03 14.05",
                    outputFolderUri = null,
                    overwriteOriginals = false,
                    status = "QUEUED",
                    failureReason = null,
                )
                val item = ConversionBatchItemEntity(
                    batchId = 0,
                    position = 0,
                    sourceUri = "content://tree/music/document/a",
                    sourceName = "a.flac",
                    sourceParentUri = null,
                    state = "PENDING",
                    outputUri = null,
                    outputName = null,
                    tagsWritten = false,
                    failureReason = null,
                    suffixedName = false,
                    result = null,
                )
                val id = history.insert(batch, listOf(item))
                assertThat(history.getBatch(id)!!.total).isEqualTo(1)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 4 index opens as version 5 keeping its library and history, with working playlists`() {
        createFromSchema(4) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation) VALUES (7, 'content://tree/music/document/b', " +
                    "'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, 30, 0, 1000, 'B', 'Band', 1, " +
                    "'Record', 2, 'Band', 1, 0)",
            )
            execSQL(
                "INSERT INTO conversion_batches (id, profile_id, performance_mode, created_at_ms, output_folder_name, " +
                    "overwrite_originals, status) VALUES (3, 'xdj-700', 'NORMAL', 5, 'Out', 0, 'DONE')",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().tracksUnderFolder("content://tree/music", "Music").map { it.id }).containsExactly(7L)
                assertThat(db.conversionHistoryDao().getBatch(3)).isNotNull()
                val playlists = db.playlistDao()
                val id = playlists.insert(
                    PlaylistEntity(name = "Mix", description = "", createdAtMs = 1, updatedAtMs = 1),
                    listOf(
                        PlaylistEntryEntity(playlistId = 0, position = 0, trackUri = "content://tree/music/document/b", title = "B", artist = "Band"),
                        PlaylistEntryEntity(playlistId = 0, position = 1, trackUri = "content://tree/music/document/b", title = "B", artist = "Band"),
                    ),
                )
                assertThat(playlists.playlist(id).first()!!.entries).hasSize(2)
                // The entries' foreign key cascades on the migrated schema too.
                db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE id = $id")
                val left = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlist_entries").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
                assertThat(left).isEqualTo(0)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 5 index opens as version 6 with its tracks marked for a technical details read`() {
        createFromSchema(5) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation) VALUES (7, 'content://tree/music/document/b', " +
                    "'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, 30, 0, 1000, 'B', 'Band', 1, " +
                    "'Record', 2, 'Band', 1, 0)",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val dao = db.libraryDao()
                val track = dao.trackByUri("content://tree/music/document/b")!!
                assertThat(track.codec).isNull()
                assertThat(track.bitrateKbps).isNull()
                assertThat(track.releaseDate).isNull()
                assertThat(track.copyright).isNull()
                assertThat(track.detailsVersion).isEqualTo(0)
                assertThat(dao.indexedFiles("content://tree/music").single().detailsVersion).isEqualTo(0)
                dao.updateTracks(listOf(track.copy(codec = "FLAC", releaseDate = "2020-12-10", detailsVersion = 1)))
                assertThat(dao.trackByUri(track.documentUri)!!.toDomain().codec).isEqualTo("FLAC")
            }
        } finally {
            db.close()
        }
    }
    @Test
    fun `a version 6 index opens as version 7 keeping its library, with empty favourites, pins and hidden items`() {
        createFromSchema(6) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation, details_version) VALUES (7, " +
                    "'content://tree/music/document/b', 'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, " +
                    "30, 0, 1000, 'B', 'Band', 1, 'Record', 2, 'Band', 1, 0, 1)",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().tracksByTitle().first().map { it.title }).containsExactly("B")
                val marks = db.libraryMarksDao()
                assertThat(marks.favourites("SONG").first()).isEmpty()
                assertThat(marks.hidden().first()).isEmpty()
                marks.insertHidden(HiddenItemEntity("SONG", "content://tree/music/document/b", "B", "Band", 1))
                marks.insertPin(PinEntity("songs", "SONG", "content://tree/music/document/b", 2))
                assertThat(db.libraryDao().tracksByTitle().first()).isEmpty()
                assertThat(marks.pins("songs").first()).containsExactly("content://tree/music/document/b")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 7 index opens as version 8 keeping its library and marks, with no plays yet`() {
        createFromSchema(7) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation, details_version) VALUES (7, " +
                    "'content://tree/music/document/b', 'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, " +
                    "30, 0, 1000, 'B', 'Band', 1, 'Record', 2, 'Band', 1, 0, 1)",
            )
            execSQL("INSERT INTO favourites (kind, item_key, added_at_ms) VALUES ('SONG', 'content://tree/music/document/b', 3)")
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().tracksByTitle().first().map { it.title }).containsExactly("B")
                assertThat(db.libraryMarksDao().favourites("SONG").first()).containsExactly("content://tree/music/document/b")
                val plays = db.playCountDao()
                assertThat(plays.all().first()).isEmpty()
                plays.recordPlay("content://tree/music/document/b", 5)
                assertThat(plays.all().first()).containsExactly(PlayCountEntity("content://tree/music/document/b", 1, 5))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 8 index opens as version 9 keeping its library and plays, with an empty import history`() {
        createFromSchema(8) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation, details_version) VALUES (7, " +
                    "'content://tree/music/document/b', 'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, " +
                    "30, 0, 1000, 'B', 'Band', 1, 'Record', 2, 'Band', 1, 0, 1)",
            )
            execSQL("INSERT INTO play_counts (track_uri, play_count, last_played_ms) VALUES ('content://tree/music/document/b', 4, 9)")
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().tracksByTitle().first().map { it.title }).containsExactly("B")
                assertThat(db.playCountDao().all().first()).containsExactly(PlayCountEntity("content://tree/music/document/b", 4, 9))
                val imports = db.archiveImportDao()
                assertThat(imports.imports().first()).isEmpty()
                val id = imports.insert(
                    ArchiveImportEntity(
                        archiveName = "Mix.zip",
                        importedAtMs = 5,
                        destinationTreeUri = "content://tree/music",
                        folderUri = "content://tree/music/document/Mix",
                        playlistId = null,
                    ),
                    listOf(ArchiveImportSongEntity(importId = 0, position = 0, documentKey = "tree/Mix/b.flac", title = "B", artist = "Band")),
                )
                assertThat(imports.getImport(id)!!.songCount).isEqualTo(1)
                // The songs' foreign key cascades on the migrated schema too.
                db.openHelper.writableDatabase.execSQL("DELETE FROM archive_imports WHERE id = $id")
                assertThat(imports.getSongs(id)).isEmpty()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 9 index opens as version 10 keeping its library and imports, with empty duplicate tables`() {
        createFromSchema(9) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO tracks (id, document_uri, folder_tree_uri, folder_path, file_name, mime_type, size_bytes, " +
                    "last_modified_ms, date_added_sec, tag_compilation, duration_ms, title, artist, artist_id, album, " +
                    "album_id, album_artist, album_artist_id, is_compilation, details_version) VALUES (7, " +
                    "'content://tree/music/document/b', 'content://tree/music', 'Music/Rock', 'b.flac', 'audio/flac', 10, 20, " +
                    "30, 0, 1000, 'B', 'Band', 1, 'Record', 2, 'Band', 1, 0, 1)",
            )
            execSQL(
                "INSERT INTO archive_imports (id, archive_name, imported_at_ms, destination_tree_uri, folder_uri, playlist_id) " +
                    "VALUES (3, 'Mix.zip', 5, 'content://tree/music', 'content://tree/music/document/Mix', NULL)",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().tracksByTitle().first().map { it.title }).containsExactly("B")
                assertThat(db.archiveImportDao().getImport(3)!!.entry.archiveName).isEqualTo("Mix.zip")
                val duplicates = db.duplicateDao()
                assertThat(duplicates.files().map { it.documentUri }).containsExactly("content://tree/music/document/b")
                assertThat(duplicates.dismissals()).isEmpty()
                assertThat(duplicates.fingerprints()).isEmpty()
                assertThat(duplicates.getSettings()).isNull()
                // The settings row's defaults come from the migrated schema too.
                db.openHelper.writableDatabase.execSQL("INSERT INTO duplicate_settings (id) VALUES (0)")
                assertThat(duplicates.getSettings()).isEqualTo(DuplicateSettingsEntity())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 10 index opens as version 11 with every converted file on its batch's profile`() {
        createFromSchema(10) {
            execSQL("INSERT INTO library_folders (tree_uri, display_name, added_at_ms) VALUES ('content://tree/music', 'Music', 1)")
            execSQL(
                "INSERT INTO conversion_batches (id, profile_id, performance_mode, created_at_ms, output_folder_name, " +
                    "output_folder_uri, overwrite_originals, status, failure_reason) VALUES " +
                    "(1, 'xdj-700', 'NORMAL', 5, '2026-10-03 14.05', 'content://out/1', 0, 'COMPLETED', NULL), " +
                    "(2, 'watch-opus', 'NORMAL', 6, '2026-10-04 10.00', NULL, 1, 'QUEUED', NULL)",
            )
            execSQL(
                "INSERT INTO conversion_batch_items (id, batch_id, position, source_uri, source_name, source_parent_uri, " +
                    "state, output_uri, output_name, tags_written, failure_reason, suffixed_name, result_source_rate_hz, " +
                    "result_target_rate_hz, result_source_channels, result_target_channels, result_target_bits, " +
                    "result_dither, result_output_frames, result_source_frames) VALUES " +
                    "(10, 1, 0, 'content://tree/music/document/a', 'a.flac', 'content://tree/music', 'DONE', " +
                    "'content://out/1/a', 'a.aiff', 1, NULL, 0, 96000, 44100, 2, 2, 16, 1, 441000, 960000), " +
                    "(11, 1, 1, 'content://tree/music/document/b', 'b.mp3', NULL, 'FAILED', NULL, NULL, 0, 'Cannot decode', 0, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                    "(20, 2, 0, 'content://tree/music/document/c', 'c.wav', NULL, 'PENDING', NULL, NULL, 0, NULL, 0, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertThat(db.libraryDao().folderList()).containsExactly(LibraryFolderEntity("content://tree/music", "Music", 1))
                val history = db.conversionHistoryDao()
                val first = history.getItems(1)
                assertThat(first.map { it.item.id }).containsExactly(10L, 11L).inOrder()
                assertThat(first.map { it.item.profileId }).containsExactly("xdj-700", "xdj-700")
                assertThat(first[0].item.outputName).isEqualTo("a.aiff")
                assertThat(first[0].item.result!!.outputFrames).isEqualTo(441_000)
                assertThat(first[1].item.failureReason).isEqualTo("Cannot decode")
                assertThat(history.getItems(2).single().item.profileId).isEqualTo("watch-opus")
                assertThat(history.getBatch(1)!!.total).isEqualTo(2)
                assertThat(history.getBatch(1)!!.done).isEqualTo(1)
                assertThat(history.getBatch(2)!!.batch.profileId).isEqualTo("watch-opus")
                // No row is left without a profile of its own.
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM conversion_batch_items WHERE profile_id IS NULL").use {
                    it.moveToFirst()
                    assertThat(it.getInt(0)).isEqualTo(0)
                }
            }
        } finally {
            db.close()
        }
    }
}
