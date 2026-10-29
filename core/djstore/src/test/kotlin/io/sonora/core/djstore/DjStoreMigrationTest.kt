package io.sonora.core.djstore

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.FileStamp
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djstore.db.DjStoreDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The migrations of the permanent store, each run on a database built from the committed schema file of the older
 * version (`schemas/`), so what a user's phone has on disk is what the test migrates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DjStoreMigrationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DjStoreDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(DjStoreDatabase.NAME)
    }

    /** Creates `sonora-dj.db` as Room created it at [version]: the tables, the master table and the user version. */
    private fun createOldDatabase(version: Int, fill: (android.database.sqlite.SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/io.sonora.core.djstore.db.DjStoreDatabase/$version.json").readText()).getJSONObject("database")
        val raw = context.openOrCreateDatabase(DjStoreDatabase.NAME, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            fill(raw)
            raw.version = version
        } finally {
            raw.close()
        }
    }

    private fun open(): DjStoreDatabase = DjStoreDatabase.builder(context).allowMainThreadQueries().build().also { opened += it }

    @Test
    fun `migrating from version 1 keeps every analysis and memo row and adds the grid edits table`() = runBlocking<Unit> {
        val stored = analysis(seed = 3, segments = 2)
        val id = audioId(3)
        val stamp = FileStamp(10, 20)
        createOldDatabase(1) { raw ->
            AnalysisRowsForTest.insert(raw, id, stored)
            raw.execSQL("INSERT INTO audio_ids (uri, size_bytes, last_modified_ms, audio_id) VALUES ('content://a', 10, 20, '${id.value}')")
        }

        val db = open()

        assertThat(RoomDjAnalysisStore(db.analysisDao()).get(id)).isEqualTo(stored)
        assertThat(RoomAudioIdIndex(db.audioIdDao()).idFor("content://a", stamp)).isEqualTo(id)
        val edits = RoomDjGridEditStore(db.gridEditDao())
        assertThat(edits.get(id)).isNull()
        edits.put(id, GridEdit(12_800, 300.0))
        assertThat(edits.get(id)).isEqualTo(GridEdit(12_800, 300.0))
    }

    @Test
    fun `the analysis row survives the migration byte for byte`() {
        val id = audioId(5)
        val stored = analysis(seed = 5)
        var before: ByteArray? = null
        createOldDatabase(1) { raw ->
            AnalysisRowsForTest.insert(raw, id, stored)
            before = AnalysisRowsForTest.gridBlob(raw, id)
        }

        open().openHelper.writableDatabase // runs the migration

        val raw = context.openOrCreateDatabase(DjStoreDatabase.NAME, Context.MODE_PRIVATE, null)
        try {
            assertThat(raw.version).isEqualTo(3)
            assertThat(AnalysisRowsForTest.gridBlob(raw, id)).isEqualTo(before)
        } finally {
            raw.close()
        }
    }

    @Test
    fun `a fresh install creates version 3 directly`() = runBlocking<Unit> {
        val db = open()

        RoomDjGridEditStore(db.gridEditDao()).put(audioId(1), GridEdit(12_000, 0.0))
        RoomDjCueStore(db.cueDao()).put(audioId(1), DjCue("hot-0", CueKind.HOT, 0, 1_000.0, null, CuePalette.defaultHot(0), ""))

        assertThat(RoomDjGridEditStore(db.gridEditDao()).get(audioId(1))).isEqualTo(GridEdit(12_000, 0.0))
        assertThat(RoomDjCueStore(db.cueDao()).get(audioId(1))).hasSize(1)
    }

    @Test
    fun `migrating from version 2 keeps the analyses, the memo and the grid edits and adds the cues table`() = runBlocking<Unit> {
        val stored = analysis(seed = 7, segments = 2)
        val id = audioId(7)
        createOldDatabase(2) { raw ->
            AnalysisRowsForTest.insert(raw, id, stored)
            raw.execSQL("INSERT INTO audio_ids (uri, size_bytes, last_modified_ms, audio_id) VALUES ('content://b', 11, 22, '${id.value}')")
            raw.execSQL("INSERT INTO grid_edits (audio_id, bpm_hundredths, anchor_ms, updated_at_ms, record_version) VALUES ('${id.value}', 12801, 99.5, 5, 1)")
        }

        val db = open()

        assertThat(RoomDjAnalysisStore(db.analysisDao()).get(id)).isEqualTo(stored)
        assertThat(RoomAudioIdIndex(db.audioIdDao()).idFor("content://b", FileStamp(11, 22))).isEqualTo(id)
        assertThat(RoomDjGridEditStore(db.gridEditDao()).get(id)).isEqualTo(GridEdit(12_801, 99.5))
        val cues = RoomDjCueStore(db.cueDao())
        assertThat(cues.get(id)).isEmpty()
        val cue = DjCue("m1", CueKind.MEMORY, null, 2_500.0, null, CuePalette.MEMORY_DEFAULT, "intro")
        cues.put(id, cue)
        assertThat(cues.get(id)).containsExactly(cue)
    }

    @Test
    fun `the rows of version 2 survive the migration byte for byte`() {
        val id = audioId(8)
        var before: ByteArray? = null
        createOldDatabase(2) { raw ->
            AnalysisRowsForTest.insert(raw, id, analysis(seed = 8))
            before = AnalysisRowsForTest.gridBlob(raw, id)
            raw.execSQL("INSERT INTO grid_edits (audio_id, bpm_hundredths, anchor_ms, updated_at_ms, record_version) VALUES ('${id.value}', 12801, 99.5, 5, 1)")
        }

        open().openHelper.writableDatabase // runs the migration

        val raw = context.openOrCreateDatabase(DjStoreDatabase.NAME, Context.MODE_PRIVATE, null)
        try {
            assertThat(raw.version).isEqualTo(3)
            assertThat(AnalysisRowsForTest.gridBlob(raw, id)).isEqualTo(before)
            raw.rawQuery("SELECT bpm_hundredths, anchor_ms, updated_at_ms FROM grid_edits", null).use {
                assertThat(it.count).isEqualTo(1)
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(12_801)
                assertThat(it.getDouble(1)).isEqualTo(99.5)
                assertThat(it.getLong(2)).isEqualTo(5L)
            }
        } finally {
            raw.close()
        }
    }

    @Test
    fun `an edit survives closing and opening the database again`() = runBlocking<Unit> {
        open().let { db ->
            RoomDjGridEditStore(db.gridEditDao()).put(audioId(1), GridEdit(12_801, 99.5))
            db.close()
        }

        assertThat(RoomDjGridEditStore(open().gridEditDao()).get(audioId(1))).isEqualTo(GridEdit(12_801, 99.5))
    }
}

/** Writes and reads analysis rows in a raw database, for tests that build the database as an older version made it. */
private object AnalysisRowsForTest {

    fun insert(raw: android.database.sqlite.SQLiteDatabase, id: io.sonora.core.djmodel.AudioId, analysis: io.sonora.core.djmodel.DjAnalysis) {
        val row = AnalysisRows.toEntity(id, analysis)
        val values = android.content.ContentValues().apply {
            put("audio_id", row.audioId)
            put("analyzer_version", row.analyzerVersion)
            put("beat_source", row.beatSource)
            put("model_id", row.modelId)
            put("accelerator", row.accelerator)
            put("created_at_ms", row.createdAtMs)
            put("duration_ms", row.durationMs)
            put("key_index", row.keyIndex)
            put("key_strength", row.keyStrength)
            put("key_method", row.keyMethod)
            put("grid", row.grid)
            put("waveform", row.waveform)
            put("waveform_version", row.waveformVersion)
            put("record_version", row.recordVersion)
        }
        check(raw.insert("analyses", null, values) != -1L)
    }

    fun gridBlob(raw: android.database.sqlite.SQLiteDatabase, id: io.sonora.core.djmodel.AudioId): ByteArray? =
        raw.rawQuery("SELECT grid FROM analyses WHERE audio_id = ?", arrayOf(id.value)).use {
            check(it.moveToFirst())
            it.getBlob(0)
        }
}
