package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djstore.db.CueEntity
import io.sonora.core.djstore.db.DjStoreDatabase
import io.sonora.core.domain.backup.DjRestoreOutcome
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The grid edits and cues in the app backup: one record per track, merged on restore, never overwriting what is there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomDjUserDataBackupTest {

    private lateinit var source: DjStoreDatabase
    private lateinit var target: DjStoreDatabase
    private lateinit var sourceEdits: RoomDjGridEditStore
    private lateinit var sourceCues: RoomDjCueStore
    private lateinit var targetEdits: RoomDjGridEditStore
    private lateinit var targetCues: RoomDjCueStore

    private fun inMemory() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @Before
    fun setUp() {
        source = inMemory()
        target = inMemory()
        sourceEdits = RoomDjGridEditStore(source.gridEditDao())
        sourceCues = RoomDjCueStore(source.cueDao())
        targetEdits = RoomDjGridEditStore(target.gridEditDao())
        targetCues = RoomDjCueStore(target.cueDao())
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun backup(db: DjStoreDatabase) = RoomDjUserDataBackup(db.gridEditDao(), db.cueDao())

    private fun hot(slot: Int, at: Double, name: String = "", loopEnd: Double? = null) =
        DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, loopEnd, CuePalette.defaultHot(slot), name)

    private fun memory(id: String, at: Double, color: CueColor = CuePalette.MEMORY_DEFAULT) =
        DjCue(id, CueKind.MEMORY, null, at, null, color, "")

    @Test
    fun `nothing stored exports nothing`() = runBlocking<Unit> {
        assertThat(backup(source).export()).isEmpty()
    }

    @Test
    fun `a track with an edit or cues is one record, in audio id order, with the prefix`() = runBlocking<Unit> {
        sourceEdits.put(audioId(2), GridEdit(12_800, 300.0))
        sourceCues.put(audioId(2), hot(0, 1_000.0))
        sourceCues.put(audioId(1), memory("m1", 500.0))

        val records = backup(source).export()

        assertThat(records).hasSize(2)
        assertThat(records).containsNoDuplicates()
        assertThat(records.all { it.startsWith(RoomDjUserDataBackup.PREFIX) }).isTrue()
        assertThat(records[0]).isNotEqualTo(records[1])
    }

    @Test
    fun `an edit and cues restore into an empty store exactly`() = runBlocking<Unit> {
        val loop = hot(3, 12_345.678, name = "Drop ñ", loopEnd = 20_000.125)
        val custom = memory("m1", 500.5, CueColor.custom(0x123456))
        sourceEdits.put(audioId(1), GridEdit(12_801, 99.5))
        sourceCues.put(audioId(1), loop)
        sourceCues.put(audioId(1), custom)

        val target = backup(target)
        for (record in backup(source).export()) assertThat(target.restore(record)).isEqualTo(RoomDjUserDataBackup.Result.ADDED)

        assertThat(targetEdits.get(audioId(1))).isEqualTo(GridEdit(12_801, 99.5))
        assertThat(targetCues.get(audioId(1))).containsExactly(custom, loop).inOrder()
    }

    @Test
    fun `a track with only cues or only an edit restores that part`() = runBlocking<Unit> {
        sourceEdits.put(audioId(1), GridEdit(12_000, 0.0))
        sourceCues.put(audioId(2), hot(0, 1_000.0))

        val target = backup(target)
        for (record in backup(source).export()) target.restore(record)

        assertThat(targetEdits.get(audioId(1))).isEqualTo(GridEdit(12_000, 0.0))
        assertThat(targetCues.get(audioId(1))).isEmpty()
        assertThat(targetEdits.get(audioId(2))).isNull()
        assertThat(targetCues.get(audioId(2))).hasSize(1)
    }

    @Test
    fun `restoring never overwrites an edit or a cue the store already has`() = runBlocking<Unit> {
        sourceEdits.put(audioId(1), GridEdit(12_000, 0.0))
        sourceCues.put(audioId(1), hot(0, 1_000.0, name = "old"))
        sourceCues.put(audioId(1), memory("m1", 5_000.0))
        targetEdits.put(audioId(1), GridEdit(17_400, 50.0))
        targetCues.put(audioId(1), hot(0, 2_000.0, name = "mine"))

        val result = backup(target).restore(backup(source).export().single())

        assertThat(result).isEqualTo(RoomDjUserDataBackup.Result.ADDED) // the memory cue was new
        assertThat(targetEdits.get(audioId(1))).isEqualTo(GridEdit(17_400, 50.0))
        assertThat(targetCues.get(audioId(1)).map { it.id to it.positionMs }).containsExactly("hot-0" to 2_000.0, "m1" to 5_000.0).inOrder()
    }

    @Test
    fun `restoring the same record twice adds nothing the second time`() = runBlocking<Unit> {
        sourceEdits.put(audioId(1), GridEdit(12_000, 0.0))
        sourceCues.put(audioId(1), hot(0, 1_000.0))
        val record = backup(source).export().single()
        val target = backup(target)

        assertThat(target.restore(record)).isEqualTo(RoomDjUserDataBackup.Result.ADDED)
        assertThat(target.restore(record)).isEqualTo(RoomDjUserDataBackup.Result.ALREADY_PRESENT)
    }

    @Test
    fun `rows this build cannot read are not exported and stay where they are`() = runBlocking<Unit> {
        source.cueDao().upsert(CueEntity(audioId(1).value, "hot-0", CueEntity.KIND_HOT, 0, 1_000.0, null, 6, 0x28E214, "", 1L, CueEntity.RECORD_VERSION + 1))

        assertThat(backup(source).export()).isEmpty()
        assertThat(source.cueDao().forAudio(audioId(1).value)).hasSize(1)
    }

    @Test
    fun `a record that is not readable is reported as such`() = runBlocking<Unit> {
        val target = backup(target)

        assertThat(target.restore("sdju1:not base64 !!")).isNull()
        assertThat(target.restore(RoomDjUserDataBackup.PREFIX + b64("not json"))).isNull()
        assertThat(target.restore(RoomDjUserDataBackup.PREFIX + b64("""{"id":"nope","cues":[]}"""))).isNull()
        assertThat(target.restore(RoomDjUserDataBackup.PREFIX + b64("""{"cues":[]}"""))).isNull()
        assertThat(target.restore("U0RKMQAB")).isNull() // an analysis record is not ours
    }

    @Test
    fun `a record from a later version or with unknown fields still restores what it can`() = runBlocking<Unit> {
        val id = audioId(1).value
        val json = """{"v":9,"id":"$id","future":true,"grid":{"bpmHundredths":12800,"anchorMs":10.0,"x":1},""" +
            """"cues":[{"id":"hot-1","kind":"hot","slot":1,"positionMs":2000.0,"loopEndMs":null,"colorIndex":6,"colorRgb":2679316,"name":"a","z":0},""" +
            """{"id":"bad","kind":"hot","slot":99,"positionMs":1.0,"colorIndex":1,"colorRgb":0,"name":""},""" +
            """{"id":"m2","kind":"memory","positionMs":9000.0,"colorIndex":2,"colorRgb":16727856,"name":""}]}"""

        val result = backup(target).restore(RoomDjUserDataBackup.PREFIX + b64(json))

        assertThat(result).isEqualTo(RoomDjUserDataBackup.Result.ADDED)
        assertThat(targetEdits.get(audioId(1))).isEqualTo(GridEdit(12_800, 10.0))
        assertThat(targetCues.get(audioId(1)).map { it.id }).containsExactly("hot-1", "m2").inOrder()
    }

    @Test
    fun `the DJ backup store carries the user data after the analyses and restores both kinds`() = runBlocking<Unit> {
        val sourceAnalyses = RoomDjAnalysisStore(source.analysisDao())
        sourceAnalyses.putIfAbsent(audioId(1), analysis(seed = 1))
        sourceEdits.put(audioId(1), GridEdit(12_000, 0.0))
        sourceCues.put(audioId(1), hot(0, 1_000.0))
        val full = RoomDjBackupStore(source.analysisDao(), sourceAnalyses, backup(source))
        val targetAnalyses = RoomDjAnalysisStore(target.analysisDao())
        val fullTarget = RoomDjBackupStore(target.analysisDao(), targetAnalyses, backup(target))

        val records = full.export()
        val outcome = fullTarget.restore(records)

        assertThat(records).hasSize(2)
        assertThat(records.last().startsWith(RoomDjUserDataBackup.PREFIX)).isTrue()
        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 2, alreadyPresent = 0, skipped = 0))
        assertThat(targetAnalyses.get(audioId(1))).isNotNull()
        assertThat(targetEdits.get(audioId(1))).isEqualTo(GridEdit(12_000, 0.0))
        assertThat(targetCues.get(audioId(1))).hasSize(1)
        assertThat(fullTarget.restore(records)).isEqualTo(DjRestoreOutcome(restored = 0, alreadyPresent = 2, skipped = 0))
    }

    @Test
    fun `without the user data part the DJ backup store behaves as before`() = runBlocking<Unit> {
        val plain = RoomDjBackupStore(target.analysisDao(), RoomDjAnalysisStore(target.analysisDao()))

        assertThat(plain.restore(listOf(RoomDjUserDataBackup.PREFIX + b64("{}")))).isEqualTo(DjRestoreOutcome(0, 0, 1))
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
}
