package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.codec.DjAnalysisCodec
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djstore.db.DjAnalysisDao
import io.sonora.core.djstore.db.DjAnalysisEntity
import io.sonora.core.djstore.db.DjStoreDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomDjAnalysisStoreTest {

    private lateinit var db: DjStoreDatabase
    private lateinit var dao: DjAnalysisDao
    private lateinit var store: RoomDjAnalysisStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analysisDao()
        store = RoomDjAnalysisStore(dao)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an unknown track has no analysis`() = runBlocking<Unit> {
        assertThat(store.get(audioId(1))).isNull()
    }

    @Test
    fun `an analysis reads back equal, every optional part included`() = runBlocking<Unit> {
        val full = analysis(seed = 1, segments = 3)
        val bare = bareAnalysis()

        assertThat(store.putIfAbsent(audioId(1), full)).isTrue()
        assertThat(store.putIfAbsent(audioId(2), bare)).isTrue()

        // Compared by decoded content (DjAnalysis equality), never by blob bytes.
        assertThat(store.get(audioId(1))).isEqualTo(full)
        assertThat(store.get(audioId(2))).isEqualTo(bare)
    }

    @Test
    fun `putIfAbsent keeps the first analysis and says it stored nothing`() = runBlocking<Unit> {
        val first = analysis(seed = 1)
        val second = analysis(seed = 2, analyzerVersion = 2)

        assertThat(store.putIfAbsent(audioId(1), first)).isTrue()
        assertThat(store.putIfAbsent(audioId(1), second)).isFalse()

        assertThat(store.get(audioId(1))).isEqualTo(first)
    }

    @Test
    fun `racing putIfAbsent calls store exactly one analysis and every one reads the winner`() = runBlocking<Unit> {
        val candidates = (1L..8L).map { analysis(seed = it) }

        val stored = candidates.map { async(Dispatchers.Default) { store.putIfAbsent(audioId(1), it) } }.awaitAll()

        assertThat(stored.count { it }).isEqualTo(1)
        assertThat(candidates).contains(store.get(audioId(1)))
    }

    @Test
    fun `replace overwrites every analysis field, including parts that go away`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1, segments = 2))

        val replacement = bareAnalysis(seed = 7)
        store.replace(audioId(1), replacement)

        val read = store.get(audioId(1))!!
        assertThat(read).isEqualTo(replacement)
        assertThat(read.grid).isNull()
        assertThat(read.key).isNull()
        assertThat(read.provenance.modelId).isNull()
    }

    @Test
    fun `replace on a track without an analysis stores it, and a later putIfAbsent leaves it alone`() = runBlocking<Unit> {
        val replaced = analysis(seed = 5)
        store.replace(audioId(1), replaced)

        assertThat(store.putIfAbsent(audioId(1), analysis(seed = 6))).isFalse()
        assertThat(store.get(audioId(1))).isEqualTo(replaced)
    }

    @Test
    fun `analyses of other tracks are untouched by a replace`() = runBlocking<Unit> {
        val other = analysis(seed = 9)
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        store.putIfAbsent(audioId(2), other)

        store.replace(audioId(1), analysis(seed = 3))

        assertThat(store.get(audioId(2))).isEqualTo(other)
    }

    @Test
    fun `observe emits none, then the analysis, then its replacement, and ignores other tracks`() = runBlocking<Unit> {
        val first = analysis(seed = 1)
        val second = analysis(seed = 2)
        val seen = CopyOnWriteArrayList<DjAnalysis?>()
        val collector = launch(Dispatchers.Default) { store.observe(audioId(1)).collect { seen += it } }

        awaitUntil { seen.size == 1 }
        // A write to another track re-runs the query, but must not add an emission.
        store.putIfAbsent(audioId(2), analysis(seed = 50))
        delay(300)
        assertThat(seen).hasSize(1)

        store.putIfAbsent(audioId(1), first)
        awaitUntil { seen.size == 2 }
        store.replace(audioId(1), second)
        awaitUntil { seen.size == 3 }
        collector.cancel()

        assertThat(seen).containsExactly(null, first, second).inOrder()
    }

    @Test
    fun `analysedAmong returns the stored subset`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        store.putIfAbsent(audioId(3), bareAnalysis())

        assertThat(store.analysedAmong(listOf(audioId(1), audioId(2), audioId(3), audioId(4))))
            .containsExactly(audioId(1), audioId(3))
        assertThat(store.analysedAmong(emptyList())).isEmpty()
    }

    @Test
    fun `analysedAmong copes with more ids than SQLite takes as variables`() = runBlocking<Unit> {
        val tiny = bareAnalysis(columns = 1)
        for (n in listOf(5, 700, 1_499)) store.putIfAbsent(audioId(n), tiny)

        val asked = (0 until 1_500).map(::audioId)

        assertThat(store.analysedAmong(asked)).containsExactly(audioId(5), audioId(700), audioId(1_499))
    }

    @Test
    fun `a waveform written by a newer format is not an analysis, and is kept as it is`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        val newer = dao.find(audioId(1).value)!!.let { it.copy(waveform = it.waveform.withFormatVersion(2)) }
        dao.upsert(newer)

        assertThat(store.get(audioId(1))).isNull()
        assertThat(store.analysedAmong(listOf(audioId(1)))).isEmpty()
        assertThat(store.observe(audioId(1)).first()).isNull()

        // The first writer still wins: the newer row is neither replaced nor repaired.
        assertThat(store.putIfAbsent(audioId(1), analysis(seed = 2))).isFalse()
        assertThat(dao.find(audioId(1).value)).isEqualTo(newer)
    }

    @Test
    fun `a grid written by a newer format is not an analysis, and is kept as it is`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        val newer = dao.find(audioId(1).value)!!.let { it.copy(grid = it.grid!!.withFormatVersion(2)) }
        dao.upsert(newer)

        assertThat(store.get(audioId(1))).isNull()
        assertThat(store.analysedAmong(listOf(audioId(1)))).isEmpty()
        assertThat(store.putIfAbsent(audioId(1), analysis(seed = 2))).isFalse()
        assertThat(dao.find(audioId(1).value)).isEqualTo(newer)
    }

    @Test
    fun `a row of a newer layout is not an analysis, and is kept as it is`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        val newer = dao.find(audioId(1).value)!!.copy(recordVersion = DjAnalysisEntity.RECORD_VERSION + 1)
        dao.upsert(newer)

        assertThat(store.get(audioId(1))).isNull()
        assertThat(store.analysedAmong(listOf(audioId(1)))).isEmpty()
        assertThat(store.putIfAbsent(audioId(1), analysis(seed = 2))).isFalse()
        assertThat(dao.find(audioId(1).value)).isEqualTo(newer)
    }

    @Test
    fun `an explicit replace does overwrite a row of a newer format`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        dao.find(audioId(1).value)!!.let { dao.upsert(it.copy(waveform = it.waveform.withFormatVersion(2))) }
        val requested = analysis(seed = 3)

        store.replace(audioId(1), requested)

        assertThat(store.get(audioId(1))).isEqualTo(requested)
    }

    @Test
    fun `a damaged row is not an analysis and putIfAbsent repairs it`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        dao.find(audioId(1).value)!!.let { row ->
            // Flip one bit in the middle of the compressed stream: the checksum no longer matches.
            val damaged = row.waveform.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            dao.upsert(row.copy(waveform = damaged))
        }

        assertThat(store.get(audioId(1))).isNull()

        val fresh = analysis(seed = 2)
        assertThat(store.putIfAbsent(audioId(1), fresh)).isTrue()
        assertThat(store.get(audioId(1))).isEqualTo(fresh)
    }

    @Test
    fun `the stored blobs are SDJ1 records of the analysis`() = runBlocking<Unit> {
        val stored = analysis(seed = 1, segments = 2)
        store.putIfAbsent(audioId(1), stored)

        val row = dao.find(audioId(1).value)!!

        // Decoded content only: the compressed waveform bytes depend on the zlib build.
        assertThat(DjAnalysisCodec.decodeGrid(row.grid!!)).isEqualTo(stored.grid)
        assertThat(DjAnalysisCodec.decodeWaveform(row.waveform)).isEqualTo(stored.waveform)
        assertThat(row.waveformVersion).isEqualTo(stored.waveform.version)
        assertThat(row.recordVersion).isEqualTo(DjAnalysisEntity.RECORD_VERSION)
        assertThat(row.keyIndex).isEqualTo(stored.key!!.key.index)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    /** The record with its u16 format version (bytes 4 and 5) set to [version]; the checksum is left stale on purpose. */
    private fun ByteArray.withFormatVersion(version: Int): ByteArray = copyOf().also {
        it[4] = (version ushr 8).toByte()
        it[5] = version.toByte()
    }
}
