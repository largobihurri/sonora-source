package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djstore.db.DjStoreDatabase
import io.sonora.core.djstore.db.GridEditEntity
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
class RoomDjGridEditStoreTest {

    private lateinit var db: DjStoreDatabase
    private var now = 1_000L
    private lateinit var store: RoomDjGridEditStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomDjGridEditStore(db.gridEditDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a track without an edit has none`() = runBlocking<Unit> {
        assertThat(store.get(audioId(1))).isNull()
        assertThat(store.observe(audioId(1)).first()).isNull()
    }

    @Test
    fun `an edit reads back exactly, the tempo to the hundredth and the anchor to the microsecond`() = runBlocking<Unit> {
        val edit = GridEdit(12_801, 1_234.567)

        store.put(audioId(1), edit)

        assertThat(store.get(audioId(1))).isEqualTo(edit)
        assertThat(store.get(audioId(1))!!.bpmHundredths).isEqualTo(12_801)
    }

    @Test
    fun `saving again replaces the edit`() = runBlocking<Unit> {
        store.put(audioId(1), GridEdit(12_000, 100.0))
        store.put(audioId(1), GridEdit(12_800, 300.0))

        assertThat(store.get(audioId(1))).isEqualTo(GridEdit(12_800, 300.0))
    }

    @Test
    fun `edits are kept per track`() = runBlocking<Unit> {
        store.put(audioId(1), GridEdit(12_000, 100.0))
        store.put(audioId(2), GridEdit(17_400, 50.0))

        assertThat(store.get(audioId(1))).isEqualTo(GridEdit(12_000, 100.0))
        assertThat(store.get(audioId(2))).isEqualTo(GridEdit(17_400, 50.0))
        assertThat(store.get(audioId(3))).isNull()
    }

    @Test
    fun `deleting an edit brings the analysed grid back and touches no other track`() = runBlocking<Unit> {
        store.put(audioId(1), GridEdit(12_000, 100.0))
        store.put(audioId(2), GridEdit(17_400, 50.0))

        store.delete(audioId(1))
        store.delete(audioId(9)) // nothing to delete: no failure

        assertThat(store.get(audioId(1))).isNull()
        assertThat(store.get(audioId(2))).isEqualTo(GridEdit(17_400, 50.0))
    }

    @Test
    fun `an edit is observed as it is saved and deleted`() = runBlocking<Unit> {
        val seen = mutableListOf<GridEdit?>()
        val flow = store.observe(audioId(1))

        seen += flow.first()
        store.put(audioId(1), GridEdit(12_000, 100.0))
        seen += withTimeout(5_000) { flow.first { it != null } }
        store.delete(audioId(1))
        seen += withTimeout(5_000) { flow.first { it == null } }

        assertThat(seen).containsExactly(null, GridEdit(12_000, 100.0), null).inOrder()
    }

    @Test
    fun `saving an edit stamps the time`() = runBlocking<Unit> {
        now = 5_555L
        store.put(audioId(1), GridEdit(12_000, 100.0))

        assertThat(db.gridEditDao().find(audioId(1).value)!!.updatedAtMs).isEqualTo(5_555L)
    }

    @Test
    fun `a new analysis of the same audio leaves its edit alone`() = runBlocking<Unit> {
        val analyses = RoomDjAnalysisStore(db.analysisDao())
        analyses.putIfAbsent(audioId(1), analysis(seed = 1))
        store.put(audioId(1), GridEdit(12_000, 100.0))

        analyses.replace(audioId(1), analysis(seed = 1, analyzerVersion = 2))

        assertThat(store.get(audioId(1))).isEqualTo(GridEdit(12_000, 100.0))
        assertThat(analyses.get(audioId(1))!!.provenance.analyzerVersion).isEqualTo(2)
    }

    @Test
    fun `a row from a later build is no edit but stays in the store, and saving replaces it`() = runBlocking<Unit> {
        val dao = db.gridEditDao()
        dao.upsert(GridEditEntity(audioId(1).value, 12_000, 100.0, 1L, recordVersion = GridEditEntity.RECORD_VERSION + 1))

        assertThat(store.get(audioId(1))).isNull()
        assertThat(dao.find(audioId(1).value)!!.recordVersion).isEqualTo(GridEditEntity.RECORD_VERSION + 1)

        store.put(audioId(1), GridEdit(12_800, 300.0))
        assertThat(store.get(audioId(1))).isEqualTo(GridEdit(12_800, 300.0))
    }

    @Test
    fun `a row with an impossible value is no edit and is kept`() = runBlocking<Unit> {
        val dao = db.gridEditDao()
        dao.upsert(GridEditEntity(audioId(1).value, bpmHundredths = 7, anchorMs = 100.0, updatedAtMs = 1L, recordVersion = 1))

        assertThat(store.get(audioId(1))).isNull()
        assertThat(dao.find(audioId(1).value)).isNotNull()
    }
}
