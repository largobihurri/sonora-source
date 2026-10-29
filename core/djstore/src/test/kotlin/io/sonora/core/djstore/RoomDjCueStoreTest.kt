package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djstore.db.CueEntity
import io.sonora.core.djstore.db.DjStoreDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomDjCueStoreTest {

    private lateinit var db: DjStoreDatabase
    private var now = 1_000L
    private lateinit var store: RoomDjCueStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomDjCueStore(db.cueDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun hot(slot: Int, at: Double, name: String = "", loopEnd: Double? = null) =
        DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, loopEnd, CuePalette.defaultHot(slot), name)

    private fun memory(id: String, at: Double, color: CueColor = CuePalette.MEMORY_DEFAULT) =
        DjCue(id, CueKind.MEMORY, null, at, null, color, "")

    @Test
    fun `a track without cues has none`() = runBlocking<Unit> {
        assertThat(store.get(audioId(1))).isEmpty()
        assertThat(store.observe(audioId(1)).first()).isEmpty()
    }

    @Test
    fun `every field of a hot cue and of a memory cue reads back exactly`() = runBlocking<Unit> {
        val loop = hot(3, 12_345.678, name = "Drop ñ", loopEnd = 20_000.125)
        val custom = memory("m1", 500.5, CueColor.custom(0x123456))

        store.put(audioId(1), loop)
        store.put(audioId(1), custom)

        assertThat(store.get(audioId(1))).containsExactly(custom, loop).inOrder()
    }

    @Test
    fun `cues come back sorted by position`() = runBlocking<Unit> {
        store.put(audioId(1), memory("late", 9_000.0))
        store.put(audioId(1), hot(0, 100.0))
        store.put(audioId(1), memory("mid", 4_000.0))

        assertThat(store.get(audioId(1)).map { it.id }).containsExactly("hot-0", "mid", "late").inOrder()
    }

    @Test
    fun `saving a cue with the same id replaces it`() = runBlocking<Unit> {
        store.put(audioId(1), hot(0, 1_000.0, name = "old"))
        store.put(audioId(1), hot(0, 2_000.0, name = "new"))

        assertThat(store.get(audioId(1))).containsExactly(hot(0, 2_000.0, name = "new"))
    }

    @Test
    fun `cues are kept per track`() = runBlocking<Unit> {
        store.put(audioId(1), hot(0, 1_000.0))
        store.put(audioId(2), hot(0, 2_000.0))

        assertThat(store.get(audioId(1)).single().positionMs).isEqualTo(1_000.0)
        assertThat(store.get(audioId(2)).single().positionMs).isEqualTo(2_000.0)
        assertThat(store.get(audioId(3))).isEmpty()
    }

    @Test
    fun `deleting a cue removes only that cue`() = runBlocking<Unit> {
        store.put(audioId(1), hot(0, 1_000.0))
        store.put(audioId(1), memory("m1", 2_000.0))
        store.put(audioId(2), hot(0, 3_000.0))

        store.delete(audioId(1), "hot-0")
        store.delete(audioId(1), "nope") // nothing to delete: no failure

        assertThat(store.get(audioId(1)).map { it.id }).containsExactly("m1")
        assertThat(store.get(audioId(2))).hasSize(1)
    }

    @Test
    fun `cues are observed as they are saved and deleted`() = runBlocking<Unit> {
        val flow = store.observe(audioId(1))
        val seen = mutableListOf<List<String>>()

        seen += flow.first().map { it.id }
        store.put(audioId(1), hot(0, 1_000.0))
        seen += withTimeout(5_000) { flow.first { it.isNotEmpty() } }.map { it.id }
        store.delete(audioId(1), "hot-0")
        seen += withTimeout(5_000) { flow.first { it.isEmpty() } }.map { it.id }

        assertThat(seen).containsExactly(emptyList<String>(), listOf("hot-0"), emptyList<String>()).inOrder()
    }

    @Test
    fun `saving a cue stamps the time`() = runBlocking<Unit> {
        now = 7_777L
        store.put(audioId(1), hot(0, 1_000.0))

        assertThat(db.cueDao().forAudio(audioId(1).value).single().updatedAtMs).isEqualTo(7_777L)
    }

    @Test
    fun `a new analysis of the same audio leaves its cues alone`() = runBlocking<Unit> {
        val analyses = RoomDjAnalysisStore(db.analysisDao())
        analyses.putIfAbsent(audioId(1), analysis(seed = 1))
        store.put(audioId(1), hot(0, 1_000.0))

        analyses.replace(audioId(1), analysis(seed = 1, analyzerVersion = 2))

        assertThat(store.get(audioId(1))).hasSize(1)
    }

    @Test
    fun `a row from a later build is not a cue but stays in the store`() = runBlocking<Unit> {
        val dao = db.cueDao()
        dao.upsert(row("hot-0", recordVersion = CueEntity.RECORD_VERSION + 1))
        store.put(audioId(1), memory("m1", 2_000.0))

        assertThat(store.get(audioId(1)).map { it.id }).containsExactly("m1")
        assertThat(dao.forAudio(audioId(1).value)).hasSize(2)
    }

    @Test
    fun `a row with an impossible value is not a cue and is kept`() = runBlocking<Unit> {
        val dao = db.cueDao()
        dao.upsert(row("hot-0", slot = 12))
        dao.upsert(row("bad-kind", kind = 9))
        dao.upsert(row("negative", positionMs = -5.0))
        dao.upsert(row("m1", kind = CueEntity.KIND_MEMORY, slot = null))

        assertThat(store.get(audioId(1)).map { it.id }).containsExactly("m1")
        assertThat(dao.forAudio(audioId(1).value)).hasSize(4)
    }

    private fun row(
        cueId: String,
        kind: Int = CueEntity.KIND_HOT,
        slot: Int? = 0,
        positionMs: Double = 1_000.0,
        recordVersion: Int = CueEntity.RECORD_VERSION,
    ) = CueEntity(audioId(1).value, cueId, kind, slot, positionMs, null, 6, 0x28E214, "", 1L, recordVersion)
}
