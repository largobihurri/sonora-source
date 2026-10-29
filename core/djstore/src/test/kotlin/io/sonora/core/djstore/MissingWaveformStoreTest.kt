package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djstore.db.DjAnalysisDao
import io.sonora.core.djstore.db.DjAnalysisEntity
import io.sonora.core.djstore.db.DjStoreDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An analysis restored from a backup has grid, key and provenance but no waveform. The store keeps it as a row whose
 * `waveform_version` is 0 (a real waveform's version is never below 1) with an SDJ1 waveform of no columns, so no schema
 * change is needed, [RoomDjAnalysisStore.get] returns it with [BandWaveform.MISSING], and the rest of the pipeline can
 * ask for the ids that still need their waveform and fill it in without touching the rest of the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MissingWaveformStoreTest {

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

    private fun withoutWaveform(source: DjAnalysis): DjAnalysis = source.copy(waveform = BandWaveform.MISSING)

    @Test
    fun `an analysis without a waveform reads back as it was stored and says its waveform is missing`() = runBlocking<Unit> {
        val restored = withoutWaveform(analysis(seed = 1, segments = 3))

        assertThat(store.putIfAbsent(audioId(1), restored)).isTrue()

        val read = store.get(audioId(1))!!
        assertThat(read).isEqualTo(restored)
        assertThat(read.waveformMissing).isTrue()
        assertThat(read.grid).isEqualTo(restored.grid)
        assertThat(read.key).isEqualTo(restored.key)
    }

    @Test
    fun `the row of a missing waveform is flagged with waveform version 0 and counts as analysed`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), withoutWaveform(analysis(seed = 1)))
        store.putIfAbsent(audioId(2), analysis(seed = 2))

        assertThat(DjAnalysisEntity.WAVEFORM_MISSING).isEqualTo(0) // the DAO queries spell it as the literal 0
        assertThat(dao.find(audioId(1).value)!!.waveformVersion).isEqualTo(0)
        assertThat(dao.find(audioId(2).value)!!.waveformVersion).isEqualTo(1)
        assertThat(store.analysedAmong(listOf(audioId(1), audioId(2)))).containsExactly(audioId(1), audioId(2))
    }

    @Test
    fun `waveformMissingAmong names only the analyses that lack their waveform`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), withoutWaveform(analysis(seed = 1)))
        store.putIfAbsent(audioId(2), analysis(seed = 2))
        store.putIfAbsent(audioId(3), withoutWaveform(bareAnalysis()))

        assertThat(store.waveformMissingAmong(listOf(audioId(1), audioId(2), audioId(3), audioId(4))))
            .containsExactly(audioId(1), audioId(3))
        assertThat(store.waveformMissingAmong(emptyList())).isEmpty()
    }

    @Test
    fun `waveformMissingAmong copes with more ids than SQLite takes as variables`() = runBlocking<Unit> {
        for (n in listOf(5, 700, 1_499)) store.putIfAbsent(audioId(n), withoutWaveform(bareAnalysis()))
        store.putIfAbsent(audioId(6), bareAnalysis(columns = 1))

        assertThat(store.waveformMissingAmong((0 until 1_500).map(::audioId))).containsExactly(audioId(5), audioId(700), audioId(1_499))
    }

    @Test
    fun `fillMissingWaveform stores the waveform and keeps grid, key and provenance`() = runBlocking<Unit> {
        val full = analysis(seed = 1, segments = 2)
        store.putIfAbsent(audioId(1), withoutWaveform(full))

        assertThat(store.fillMissingWaveform(audioId(1), full.waveform)).isTrue()

        val read = store.get(audioId(1))!!
        assertThat(read).isEqualTo(full)
        assertThat(read.waveformMissing).isFalse()
        assertThat(store.waveformMissingAmong(listOf(audioId(1)))).isEmpty()
        assertThat(dao.find(audioId(1).value)!!.waveformVersion).isEqualTo(full.waveform.version)
    }

    @Test
    fun `fillMissingWaveform never replaces a waveform that is there, nor invents a row`() = runBlocking<Unit> {
        val whole = analysis(seed = 1)
        store.putIfAbsent(audioId(1), whole)

        assertThat(store.fillMissingWaveform(audioId(1), waveform(columns = 30, seed = 99))).isFalse()
        assertThat(store.fillMissingWaveform(audioId(2), waveform(columns = 30, seed = 99))).isFalse()

        assertThat(store.get(audioId(1))).isEqualTo(whole)
        assertThat(store.get(audioId(2))).isNull()
    }

    @Test
    fun `fillMissingWaveform refuses a waveform without columns`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), withoutWaveform(analysis(seed = 1)))

        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.fillMissingWaveform(audioId(1), BandWaveform.MISSING) } }
    }

    @Test
    fun `racing fills store exactly one waveform`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), withoutWaveform(analysis(seed = 1)))
        val candidates = (10L..17L).map { waveform(columns = 60, seed = it) }

        val stored = candidates.map { async(Dispatchers.Default) { store.fillMissingWaveform(audioId(1), it) } }.awaitAll()

        assertThat(stored.count { it }).isEqualTo(1)
        assertThat(candidates).contains(store.get(audioId(1))!!.waveform)
    }

    @Test
    fun `a replace with a full analysis completes a restored row`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), withoutWaveform(analysis(seed = 1)))
        val reanalysed = analysis(seed = 4)

        store.replace(audioId(1), reanalysed)

        assertThat(store.get(audioId(1))).isEqualTo(reanalysed)
        assertThat(store.waveformMissingAmong(listOf(audioId(1)))).isEmpty()
    }

    @Test
    fun `a row flagged as missing whose blob holds columns is damaged, not an analysis`() = runBlocking<Unit> {
        store.putIfAbsent(audioId(1), analysis(seed = 1))
        dao.upsert(dao.find(audioId(1).value)!!.copy(waveformVersion = 0))

        assertThat(store.get(audioId(1))).isNull()
    }
}
