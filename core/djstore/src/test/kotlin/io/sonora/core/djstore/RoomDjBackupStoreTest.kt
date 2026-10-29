package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.codec.DjAnalysisCodec
import io.sonora.core.djengine.codec.DjBackupRecord
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djstore.db.DjAnalysisDao
import io.sonora.core.djstore.db.DjAnalysisEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomDjBackupStoreTest {

    private lateinit var source: DjStoreDatabase
    private lateinit var target: DjStoreDatabase
    private lateinit var sourceStore: RoomDjAnalysisStore
    private lateinit var targetStore: RoomDjAnalysisStore
    private lateinit var sourceBackup: RoomDjBackupStore
    private lateinit var targetBackup: RoomDjBackupStore
    private lateinit var sourceDao: DjAnalysisDao

    private fun inMemory() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @Before
    fun setUp() {
        source = inMemory()
        target = inMemory()
        sourceDao = source.analysisDao()
        sourceStore = RoomDjAnalysisStore(sourceDao)
        targetStore = RoomDjAnalysisStore(target.analysisDao())
        sourceBackup = RoomDjBackupStore(sourceDao, sourceStore)
        targetBackup = RoomDjBackupStore(target.analysisDao(), targetStore)
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun decode(record: String): DjBackupRecord = DjAnalysisCodec.decodeBackup(Base64.getDecoder().decode(record))

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun withoutWaveform(source: DjAnalysis) = source.copy(waveform = BandWaveform.MISSING)

    @Test
    fun `an empty store exports nothing`() = runBlocking<Unit> {
        assertThat(sourceBackup.export()).isEmpty()
    }

    @Test
    fun `each analysis exports as one base64 SDJ1 backup record, with its duration and without a waveform`() = runBlocking<Unit> {
        val full = analysis(seed = 1, columns = 54_000, segments = 3)
        sourceStore.putIfAbsent(audioId(1), full)

        val records = sourceBackup.export()

        assertThat(records).hasSize(1)
        val record = decode(records.single())
        assertThat(record.audioId).isEqualTo(audioId(1))
        assertThat(record.grid).isEqualTo(full.grid)
        assertThat(record.key).isEqualTo(full.key)
        assertThat(record.provenance).isEqualTo(full.provenance)
        // The duration travels as a count of 1 ms frames: the codec is frozen and the store has no sample rate.
        assertThat(record.sampleRate).isEqualTo(RoomDjBackupStore.DURATION_FRAMES_PER_SECOND)
        assertThat(record.sampleRate).isEqualTo(1000)
        assertThat(record.totalFrames).isEqualTo(full.durationMs)
        // A six-minute waveform is about 100 kB in the store; the record holds none of it.
        assertThat(Base64.getDecoder().decode(records.single()).size).isLessThan(400)
    }

    @Test
    fun `a backup round trip restores every analysis but its waveform`() = runBlocking<Unit> {
        val full = analysis(seed = 1, segments = 3)
        val bare = bareAnalysis()
        sourceStore.putIfAbsent(audioId(1), full)
        sourceStore.putIfAbsent(audioId(2), bare)

        val outcome = targetBackup.restore(sourceBackup.export())

        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 2, alreadyPresent = 0, skipped = 0))
        assertThat(targetStore.get(audioId(1))).isEqualTo(withoutWaveform(full))
        assertThat(targetStore.get(audioId(2))).isEqualTo(withoutWaveform(bare))
    }

    @Test
    fun `restored rows report that their waveform is missing`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        sourceStore.putIfAbsent(audioId(2), analysis(seed = 2))
        targetStore.putIfAbsent(audioId(3), analysis(seed = 3))

        targetBackup.restore(sourceBackup.export())

        assertThat(targetStore.get(audioId(1))!!.waveformMissing).isTrue()
        assertThat(targetStore.get(audioId(3))!!.waveformMissing).isFalse()
        assertThat(targetStore.waveformMissingAmong(listOf(audioId(1), audioId(2), audioId(3))))
            .containsExactly(audioId(1), audioId(2))
        assertThat(targetStore.analysedAmong(listOf(audioId(1), audioId(2), audioId(3)))).containsExactly(audioId(1), audioId(2), audioId(3))
    }

    @Test
    fun `restoring never overwrites an analysis the store already has`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        sourceStore.putIfAbsent(audioId(2), analysis(seed = 2))
        val mine = analysis(seed = 50, analyzerVersion = 7)
        targetStore.putIfAbsent(audioId(1), mine)

        val outcome = targetBackup.restore(sourceBackup.export())

        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 1, alreadyPresent = 1, skipped = 0))
        // Whole, waveform included.
        assertThat(targetStore.get(audioId(1))).isEqualTo(mine)
        assertThat(targetStore.get(audioId(2))).isEqualTo(withoutWaveform(analysis(seed = 2)))
    }

    @Test
    fun `restoring the same backup twice changes nothing the second time`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        val records = sourceBackup.export()
        targetBackup.restore(records)
        targetStore.fillMissingWaveform(audioId(1), waveform(columns = 30, seed = 8))
        val filled = targetStore.get(audioId(1))

        val again = targetBackup.restore(records)

        assertThat(again).isEqualTo(DjRestoreOutcome(restored = 0, alreadyPresent = 1, skipped = 0))
        assertThat(targetStore.get(audioId(1))).isEqualTo(filled)
    }

    @Test
    fun `a record that is not base64 or not a record is skipped and counted, and the rest is restored`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        sourceStore.putIfAbsent(audioId(2), analysis(seed = 2))
        val (good1, good2) = sourceBackup.export()
        val bytes = Base64.getDecoder().decode(good1)
        val flipped = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        // A record of a later codec version (its checksum is stale on purpose: the version is judged first).
        val later = bytes.copyOf().also { it[4] = 0; it[5] = 2 }
        val wrongKind = DjAnalysisCodec.encodeGrid(grid())
        val damaged = listOf(
            "",
            "   ",
            "not base64 at all!",
            "QUJD",
            encode(bytes.copyOf(bytes.size - 5)),
            encode(flipped),
            encode(later),
            encode(wrongKind),
            encode(ByteArray(0)),
        )

        val outcome = targetBackup.restore(damaged.take(4) + good1 + damaged.drop(4) + good2)

        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 2, alreadyPresent = 0, skipped = damaged.size))
        assertThat(targetStore.get(audioId(1))).isNotNull()
        assertThat(targetStore.get(audioId(2))).isNotNull()
    }

    @Test
    fun `a record that repeats an id in the same backup restores the first and keeps it`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        val record = sourceBackup.export().single()

        val outcome = targetBackup.restore(listOf(record, record))

        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 1, alreadyPresent = 1, skipped = 0))
    }

    @Test
    fun `a record that carries a real sample rate converts its frames to milliseconds`() = runBlocking<Unit> {
        val record = DjBackupRecord(audioId(1), sampleRate = 44_100, totalFrames = 44_100L * 200, grid = grid(), key = null, provenance = analysis().provenance)

        targetBackup.restore(listOf(encode(DjAnalysisCodec.encodeBackup(record))))

        assertThat(targetStore.get(audioId(1))!!.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `a record whose duration overflows is skipped`() = runBlocking<Unit> {
        val record = DjBackupRecord(audioId(1), sampleRate = 1, totalFrames = Long.MAX_VALUE, grid = null, key = null, provenance = analysis().provenance)

        val outcome = targetBackup.restore(listOf(encode(DjAnalysisCodec.encodeBackup(record))))

        assertThat(outcome).isEqualTo(DjRestoreOutcome(restored = 0, alreadyPresent = 0, skipped = 1))
        assertThat(targetStore.get(audioId(1))).isNull()
    }

    @Test
    fun `rows this build cannot read are left out of the export`() = runBlocking<Unit> {
        sourceStore.putIfAbsent(audioId(1), analysis(seed = 1))
        sourceStore.putIfAbsent(audioId(2), analysis(seed = 2))
        sourceStore.putIfAbsent(audioId(3), analysis(seed = 3))
        sourceStore.putIfAbsent(audioId(4), analysis(seed = 4))
        sourceDao.upsert(sourceDao.find(audioId(2).value)!!.copy(recordVersion = DjAnalysisEntity.RECORD_VERSION + 1))
        sourceDao.upsert(sourceDao.find(audioId(3).value)!!.let { it.copy(grid = it.grid!!.copyOf(it.grid!!.size - 3)) })

        assertThat(sourceBackup.export().map { decode(it).audioId }).containsExactly(audioId(1), audioId(4))
    }

    @Test
    fun `an analysis that was itself restored exports again, so a backup of a restore keeps the grid and key`() = runBlocking<Unit> {
        val full = analysis(seed = 1, segments = 2)
        sourceStore.putIfAbsent(audioId(1), withoutWaveform(full))

        val record = decode(sourceBackup.export().single())

        assertThat(record.grid).isEqualTo(full.grid)
        assertThat(record.totalFrames).isEqualTo(full.durationMs)
    }

    @Test
    fun `more rows than one page export in full, once each, in id order`() = runBlocking<Unit> {
        val tiny = bareAnalysis(columns = 3)
        for (n in 0 until 1_030) sourceStore.putIfAbsent(audioId(n), tiny)

        val ids = sourceBackup.export().map { decode(it).audioId }

        assertThat(ids).containsExactlyElementsIn((0 until 1_030).map(::audioId)).inOrder()
    }

    @Test
    fun `20000 tracks fit in the backup well under its cap, even when each has a tempo-changing grid`() {
        fun record(n: Int, segments: Int): String {
            val full = analysis(seed = n.toLong(), segments = segments)
            val backup = DjBackupRecord(audioId(n), 1000, full.durationMs, full.grid, full.key, full.provenance)
            return encode(DjAnalysisCodec.encodeBackup(backup))
        }
        // The text of one array entry is its characters plus the two quotes and the comma around it in the JSON file.
        fun fileBytes(segments: Int): Long = (0 until 20_000).sumOf { record(it, segments).length + 3L }

        val typical = fileBytes(segments = 1)
        val variable = fileBytes(segments = 20)

        assertThat(typical).isLessThan(8L * 1024 * 1024)
        assertThat(variable).isLessThan(16L * 1024 * 1024)
    }
}
