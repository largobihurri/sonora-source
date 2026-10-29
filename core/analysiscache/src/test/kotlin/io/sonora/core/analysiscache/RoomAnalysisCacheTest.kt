package io.sonora.core.analysiscache

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.analysiscache.db.AnalysisCacheDao
import io.sonora.core.analysiscache.db.AnalysisCacheDatabase
import io.sonora.core.analysiscache.db.AnalysisEntity
import io.sonora.core.analysiscache.db.EntrySize
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomAnalysisCacheTest {

    private lateinit var db: AnalysisCacheDatabase
    private lateinit var dao: AnalysisCacheDao
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AnalysisCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analysisCacheDao()
    }

    @After
    fun tearDown() = db.close()

    /** A cache whose clock moves on 1 s per call, so every write and read has its own time. */
    private fun cache(maxBytes: Long = RoomAnalysisCache.DEFAULT_MAX_BYTES) =
        RoomAnalysisCache(dao, maxBytes) { nowMs.also { nowMs += 1_000 } }

    @Test
    fun `an analysis reads back equal, waveform bytes included`() = runTest {
        val cache = cache()
        val withKey = analysis(detailColumns = 4_500, key = MusicalKey.G_SHARP_MINOR)
        val withoutKey = analysis(detailColumns = 10, key = null, version = "other/1")

        cache.put(SHA_ONE, withKey)
        cache.put(SHA_TWO, withoutKey)

        assertThat(cache.get(SHA_ONE, VERSION)).isEqualTo(withKey)
        assertThat(cache.get(SHA_TWO, "other/1")).isEqualTo(withoutKey)
    }

    @Test
    fun `entries are keyed by content and analyser version`() = runTest {
        val cache = cache()
        cache.put(SHA_ONE, analysis())

        assertThat(cache.get(SHA_TWO, VERSION)).isNull()
        assertThat(cache.get(SHA_ONE, "sonora-analysis/3")).isNull()
    }

    @Test
    fun `storing the same key again replaces the entry`() = runTest {
        val cache = cache()
        cache.put(SHA_ONE, analysis(bpm = 120.0))

        cache.put(SHA_ONE, analysis(bpm = 126.0))

        assertThat(cache.get(SHA_ONE, VERSION)!!.bpm).isEqualTo(126.0)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `a read marks the entry used and keeps its creation time`() = runTest {
        val cache = cache()
        cache.put(SHA_ONE, analysis())
        val stored = dao.find(SHA_ONE, VERSION)!!

        cache.get(SHA_ONE, VERSION)

        val read = dao.find(SHA_ONE, VERSION)!!
        assertThat(read.createdAtMs).isEqualTo(stored.createdAtMs)
        assertThat(read.lastUsedMs).isGreaterThan(stored.lastUsedMs)
    }

    @Test
    fun `beyond its size bound the least recently used entries go first`() = runTest {
        val one = analysis(detailColumns = 1_000)
        val perEntry = RoomAnalysisCache.sizeOf(one, VERSION)
        val cache = cache(maxBytes = perEntry * 3)
        cache.put(SHA_ONE, one)
        cache.put(SHA_TWO, one)
        cache.put(SHA_THREE, one)
        cache.get(SHA_ONE, VERSION) // now SHA_TWO is the least recently used

        cache.put(SHA_FOUR, one)

        assertThat(cache.get(SHA_TWO, VERSION)).isNull()
        assertThat(cache.get(SHA_ONE, VERSION)).isNotNull()
        assertThat(cache.get(SHA_THREE, VERSION)).isNotNull()
        assertThat(cache.get(SHA_FOUR, VERSION)).isNotNull()
        assertThat(dao.totalBytes()).isAtMost(perEntry * 3)
    }

    @Test
    fun `an analysis too large to read back in one row is not stored`() = runTest {
        val cache = cache()
        val columns = (RoomAnalysisCache.MAX_ENTRY_BYTES + 1).toInt()

        cache.put(SHA_ONE, analysis(detailColumns = columns))

        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `pruning keeps only the given analyser version`() = runTest {
        val cache = cache()
        cache.put(SHA_ONE, analysis(version = "sonora-analysis/1"))
        cache.put(SHA_TWO, analysis(version = "sonora-analysis/1"))
        cache.put(SHA_ONE, analysis())

        cache.prune(VERSION)

        assertThat(dao.count()).isEqualTo(1)
        assertThat(cache.get(SHA_ONE, VERSION)).isNotNull()
    }

    @Test
    fun `remove deletes one entry`() = runTest {
        val cache = cache()
        cache.put(SHA_ONE, analysis())
        cache.put(SHA_TWO, analysis())

        cache.remove(SHA_ONE, VERSION)

        assertThat(cache.get(SHA_ONE, VERSION)).isNull()
        assertThat(cache.get(SHA_TWO, VERSION)).isNotNull()
    }

    @Test
    fun `a row that does not make an analysis is a miss and is deleted`() = runTest {
        val cache = cache()
        dao.upsert(row(SHA_ONE, preview = ByteArray(3)))
        dao.upsert(row(SHA_TWO, keyIndex = 99))
        dao.upsert(row(SHA_THREE, firstBeatInBar = 0))
        dao.upsert(row(SHA_FOUR, durationMs = -1))

        for (sha in listOf(SHA_ONE, SHA_TWO, SHA_THREE, SHA_FOUR)) assertThat(cache.get(sha, VERSION)).isNull()

        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `a failure to mark a read entry used still returns it and keeps the row`() = runTest {
        cache().put(SHA_ONE, analysis())
        val failing = FailingDao(dao, failTouch = true)
        val touchFails = RoomAnalysisCache(failing) { nowMs }

        assertThat(touchFails.get(SHA_ONE, VERSION)).isEqualTo(analysis())

        assertThat(failing.touchAttempts).isEqualTo(1)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `a row that cannot be read back is a miss and is deleted`() = runTest {
        dao.upsert(row(SHA_ONE))
        dao.upsert(row(SHA_TWO))
        val findFails = RoomAnalysisCache(FailingDao(dao, failFind = SHA_ONE)) { nowMs }

        assertThat(findFails.get(SHA_ONE, VERSION)).isNull()

        assertThat(dao.find(SHA_ONE, VERSION)).isNull()
        assertThat(dao.find(SHA_TWO, VERSION)).isNotNull()
    }

    /**
     * The real failure behind the test above, where the SQLite in use enforces Android's 2 MB cursor window (Robolectric's
     * native mode does); skipped where it does not, since the limit is the environment's, not the cache's.
     */
    @Test
    fun `a row larger than the cursor window is a miss and is deleted, where the window is enforced`() = runTest {
        val cache = cache()
        // Written directly: put() never stores an entry this large.
        dao.upsert(row(SHA_ONE, detail = ByteArray(3 shl 20)))
        assumeTrue(
            "This SQLite does not enforce the cursor window",
            runCatching { dao.find(SHA_ONE, VERSION) }.exceptionOrNull() is SQLiteException,
        )

        assertThat(cache.get(SHA_ONE, VERSION)).isNull()

        assertThat(dao.count()).isEqualTo(0)
    }

    /**
     * Uses are ordered as they happen, not by the wall clock: an entry read or stored after the clock was set back is
     * still the most recently used one.
     */
    @Test
    fun `eviction follows the order of use when the clock goes backwards`() = runTest {
        val one = analysis(detailColumns = 1_000)
        val perEntry = RoomAnalysisCache.sizeOf(one, VERSION)
        var clockMs = 10_000L
        val cache = RoomAnalysisCache(dao, maxBytes = perEntry * 3) { clockMs }
        cache.put(SHA_ONE, one)
        clockMs += 1_000
        cache.put(SHA_TWO, one)
        clockMs += 1_000
        cache.put(SHA_THREE, one)
        clockMs = 1_000 // the clock is set back

        cache.get(SHA_ONE, VERSION) // now SHA_TWO is the least recently used
        cache.put(SHA_FOUR, one)

        assertThat(dao.find(SHA_TWO, VERSION)).isNull()
        assertThat(dao.find(SHA_ONE, VERSION)).isNotNull()
        assertThat(dao.find(SHA_THREE, VERSION)).isNotNull()
        assertThat(dao.find(SHA_FOUR, VERSION)).isNotNull()
    }

    @Test
    fun `entries used at the same instant keep their order of use`() = runTest {
        val one = analysis(detailColumns = 1_000)
        val perEntry = RoomAnalysisCache.sizeOf(one, VERSION)
        val cache = RoomAnalysisCache(dao, maxBytes = perEntry * 2) { 5_000L }
        cache.put(SHA_ONE, one)
        cache.put(SHA_TWO, one)

        cache.get(SHA_ONE, VERSION) // now SHA_TWO is the least recently used
        cache.put(SHA_THREE, one)

        assertThat(dao.find(SHA_TWO, VERSION)).isNull()
        assertThat(dao.find(SHA_ONE, VERSION)).isNotNull()
        assertThat(dao.find(SHA_THREE, VERSION)).isNotNull()
    }

    private fun row(
        sha: String,
        preview: ByteArray = ByteArray(BlueWaveform.PREVIEW_COLUMNS),
        keyIndex: Int? = 1,
        firstBeatInBar: Int = 1,
        durationMs: Long = 1_000,
        detail: ByteArray = ByteArray(150),
    ) = AnalysisEntity(
        contentSha256 = sha,
        analyzerVersion = VERSION,
        bpm = 120.0,
        firstBeatMs = 0.0,
        firstBeatInBar = firstBeatInBar,
        beatCount = 2,
        keyIndex = keyIndex,
        durationMs = durationMs,
        waveformDetail = detail,
        waveformPreview = preview,
        waveformTinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS),
        sizeBytes = 1_000,
        createdAtMs = 1,
        lastUsedMs = 1,
    )

    /**
     * [real], counting [touchAttempts], except that [touch] fails when [failTouch] and [find] fails for [failFind] like a row that cannot be read
     * back.
     */
    private class FailingDao(
        private val real: AnalysisCacheDao,
        private val failTouch: Boolean = false,
        private val failFind: String? = null,
    ) : AnalysisCacheDao() {
        var touchAttempts = 0
            private set

        override suspend fun find(contentSha256: String, analyzerVersion: String): AnalysisEntity? {
            if (contentSha256 == failFind) throw SQLiteBlobTooBigException("Row too big to fit into CursorWindow")
            return real.find(contentSha256, analyzerVersion)
        }

        override suspend fun upsert(entity: AnalysisEntity) = real.upsert(entity)

        override suspend fun touch(contentSha256: String, analyzerVersion: String, nowMs: Long) {
            touchAttempts++
            if (failTouch) throw SQLiteDiskIOException("disk I/O error")
            real.touch(contentSha256, analyzerVersion, nowMs)
        }

        override suspend fun useStamp(nowMs: Long): Long = real.useStamp(nowMs)

        override suspend fun delete(contentSha256: String, analyzerVersion: String) =
            real.delete(contentSha256, analyzerVersion)

        override suspend fun deleteOtherVersions(keepAnalyzerVersion: String): Int =
            real.deleteOtherVersions(keepAnalyzerVersion)

        override suspend fun totalBytes(): Long = real.totalBytes()

        override suspend fun count(): Int = real.count()

        override suspend fun leastRecentlyUsedFirst(): List<EntrySize> = real.leastRecentlyUsedFirst()
    }

    private companion object {
        const val VERSION = "sonora-analysis/2 (test)"
        val SHA_ONE = "1".repeat(64)
        val SHA_TWO = "2".repeat(64)
        val SHA_THREE = "3".repeat(64)
        val SHA_FOUR = "4".repeat(64)

        fun analysis(
            detailColumns: Int = 1_500,
            key: MusicalKey? = MusicalKey.A_MINOR,
            bpm: Double = 128.0,
            version: String = VERSION,
        ) = TrackAnalysis(
            beatGrid = BeatGrid(bpm = bpm, firstBeatMs = 37.25, firstBeatInBar = 3, beatCount = 21),
            key = key,
            waveform = BlueWaveform(
                detail = ByteArray(detailColumns) { BlueWaveform.packColumn(it % 32, it % 8) },
                preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { BlueWaveform.packColumn(it % 32, it % 7) },
                tinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
            ),
            durationMs = detailColumns * 1_000L / BlueWaveform.DETAIL_COLUMNS_PER_SECOND,
            analyzerVersion = version,
        )
    }
}
