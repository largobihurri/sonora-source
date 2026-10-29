package io.sonora.core.djstore

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djstore.db.DjStoreDatabase
import io.sonora.core.djstore.di.DjStoreModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DjStoreDatabaseTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DjStoreDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(DjStoreDatabase.NAME)
    }

    private fun open(): DjStoreDatabase = DjStoreDatabase.builder(context).allowMainThreadQueries().build().also { opened += it }

    @Test
    fun `the database is the sonora-dj file`() {
        assertThat(DjStoreDatabase.NAME).isEqualTo("sonora-dj.db")

        runBlocking { RoomDjAnalysisStore(open().analysisDao()).get(audioId(1)) }

        assertThat(context.getDatabasePath("sonora-dj.db").exists()).isTrue()
    }

    @Test
    fun `analyses and the memo are still there after the database is closed and opened again`() = runBlocking<Unit> {
        val stored = analysis(seed = 1, segments = 2)
        val stamp = io.sonora.core.djmodel.FileStamp(10, 20)
        open().let { db ->
            RoomDjAnalysisStore(db.analysisDao()).putIfAbsent(audioId(1), stored)
            RoomAudioIdIndex(db.audioIdDao()).remember("content://a", audioId(1), stamp)
            db.close()
        }

        val reopened = open()

        assertThat(RoomDjAnalysisStore(reopened.analysisDao()).get(audioId(1))).isEqualTo(stored)
        assertThat(RoomAudioIdIndex(reopened.audioIdDao()).idFor("content://a", stamp)).isEqualTo(audioId(1))
    }

    @Test
    fun `a database from a later schema is not wiped, it fails to open and keeps its rows`() {
        val raw = context.openOrCreateDatabase(DjStoreDatabase.NAME, Context.MODE_PRIVATE, null)
        raw.execSQL("CREATE TABLE analyses (audio_id TEXT NOT NULL PRIMARY KEY)")
        raw.execSQL("INSERT INTO analyses (audio_id) VALUES ('ap1:keep-me')")
        raw.version = 99
        raw.close()

        // Room refuses to go from 99 to 1 without a migration, and none (destructive or not) is configured.
        assertThrows(Exception::class.java) {
            runBlocking { RoomDjAnalysisStore(open().analysisDao()).get(audioId(1)) }
        }

        val after = context.openOrCreateDatabase(DjStoreDatabase.NAME, Context.MODE_PRIVATE, null)
        try {
            assertThat(after.version).isEqualTo(99)
            after.rawQuery("SELECT audio_id FROM analyses", null).use {
                assertThat(it.count).isEqualTo(1)
                it.moveToFirst()
                assertThat(it.getString(0)).isEqualTo("ap1:keep-me")
            }
        } finally {
            after.close()
        }
    }

    @Test
    fun `the module hands out one database and stores built on it`() = runBlocking<Unit> {
        val db = DjStoreModule.provideDatabase(context).also { opened += it }
        val store = DjStoreModule.provideAnalysisStore(db)
        val index = DjStoreModule.provideAudioIdIndex(db)
        val stored = analysis(seed = 4)

        store.putIfAbsent(audioId(4), stored)
        index.remember("content://b", audioId(4))

        assertThat(store.get(audioId(4))).isEqualTo(stored)
        assertThat(index.idFor("content://b")).isEqualTo(audioId(4))
        assertThat(context.getDatabasePath(DjStoreDatabase.NAME).exists()).isTrue()
    }

    @Test
    fun `the module's backup store exports what the analysis store holds`() = runBlocking<Unit> {
        val db = DjStoreModule.provideDatabase(context).also { opened += it }
        val store = DjStoreModule.provideAnalysisStore(db)
        val backup = DjStoreModule.provideBackupStore(db, store)
        store.putIfAbsent(audioId(4), analysis(seed = 4))

        assertThat(backup.export()).hasSize(1)
    }

    @Test
    fun `no source of the module configures a destructive migration`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

        assertThat(sources).isNotEmpty()
        for (source in sources) {
            assertThat(source.readText()).doesNotContain("fallbackToDestructive")
            assertThat(source.readText()).doesNotContain("DestructiveMigration")
        }
    }

    @Test
    fun `schema version 1 is exported and committed`() {
        val schema = File("schemas/io.sonora.core.djstore.db.DjStoreDatabase/1.json")

        assertThat(schema.isFile).isTrue()
        val json = schema.readText()
        assertThat(json).contains("\"version\": 1")
        assertThat(json).contains("\"tableName\": \"analyses\"")
        assertThat(json).contains("\"tableName\": \"audio_ids\"")
        for (column in listOf(
            "audio_id", "analyzer_version", "beat_source", "created_at_ms", "duration_ms", "key_index", "key_strength",
            "key_method", "grid", "waveform", "waveform_version", "record_version", "uri", "size_bytes", "last_modified_ms",
        )) {
            assertThat(json).contains("\"columnName\": \"$column\"")
        }
    }

    @Test
    fun `schema version 2 is exported and committed with the grid edits table`() {
        val schema = File("schemas/io.sonora.core.djstore.db.DjStoreDatabase/2.json")

        assertThat(schema.isFile).isTrue()
        val json = schema.readText()
        assertThat(json).contains("\"version\": 2")
        assertThat(json).contains("\"tableName\": \"grid_edits\"")
        for (column in listOf("audio_id", "bpm_hundredths", "anchor_ms", "updated_at_ms", "record_version")) {
            assertThat(json).contains("\"columnName\": \"$column\"")
        }
    }

    @Test
    fun `schema version 3 is exported and committed with the cues table`() {
        val schema = File("schemas/io.sonora.core.djstore.db.DjStoreDatabase/3.json")

        assertThat(schema.isFile).isTrue()
        val json = schema.readText()
        assertThat(json).contains("\"version\": 3")
        assertThat(json).contains("\"tableName\": \"cues\"")
        for (column in listOf(
            "audio_id", "cue_id", "kind", "slot", "position_ms", "loop_end_ms", "color_index", "color_rgb", "name",
            "updated_at_ms", "record_version",
        )) {
            assertThat(json).contains("\"columnName\": \"$column\"")
        }
    }

    @Test
    fun `the module hands out a cue store on the same database`() = runBlocking<Unit> {
        val db = DjStoreModule.provideDatabase(context).also { opened += it }
        val cues = DjStoreModule.provideCueStore(db)
        val cue = io.sonora.core.djmodel.DjCue(
            "hot-1", io.sonora.core.djmodel.CueKind.HOT, 1, 10.0, null, io.sonora.core.djmodel.CuePalette.defaultHot(1), "",
        )

        cues.put(audioId(4), cue)

        assertThat(cues.get(audioId(4))).containsExactly(cue)
    }

    @Test
    fun `the module hands out a grid edit store on the same database`() = runBlocking<Unit> {
        val db = DjStoreModule.provideDatabase(context).also { opened += it }
        val edits = DjStoreModule.provideGridEditStore(db)

        edits.put(audioId(4), io.sonora.core.djmodel.GridEdit(12_000, 10.0))

        assertThat(edits.get(audioId(4))).isEqualTo(io.sonora.core.djmodel.GridEdit(12_000, 10.0))
    }
}
