package io.sonora.core.djstore

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.FileStamp
import io.sonora.core.djstore.db.DjStoreDatabase
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
class RoomAudioIdIndexTest {

    private lateinit var db: DjStoreDatabase
    private lateinit var index: RoomAudioIdIndex

    private val uri = "content://media/external/audio/media/42"
    private val stamp = FileStamp(sizeBytes = 8_123_456, lastModifiedMs = 1_700_000_000_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DjStoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        index = RoomAudioIdIndex(db.audioIdDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a file that was never identified is a miss`() = runBlocking<Unit> {
        assertThat(index.idFor(uri, stamp)).isNull()
        assertThat(index.idFor(uri)).isNull()
    }

    @Test
    fun `a file that looks as it did is a hit`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)

        assertThat(index.idFor(uri, stamp)).isEqualTo(audioId(1))
        assertThat(index.idFor(uri, stamp.copy())).isEqualTo(audioId(1))
    }

    @Test
    fun `a file whose size changed is a miss`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)

        assertThat(index.idFor(uri, stamp.copy(sizeBytes = stamp.sizeBytes + 1))).isNull()
    }

    @Test
    fun `a file whose last modified time changed, even backwards, is a miss`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)

        assertThat(index.idFor(uri, stamp.copy(lastModifiedMs = stamp.lastModifiedMs + 1))).isNull()
        assertThat(index.idFor(uri, stamp.copy(lastModifiedMs = stamp.lastModifiedMs - 1))).isNull()
    }

    @Test
    fun `remembering again replaces the id and the stamp`() = runBlocking<Unit> {
        val edited = FileStamp(sizeBytes = 9_000_000, lastModifiedMs = 1_800_000_000_000)
        index.remember(uri, audioId(1), stamp)

        index.remember(uri, audioId(2), edited)

        assertThat(index.idFor(uri, edited)).isEqualTo(audioId(2))
        assertThat(index.idFor(uri, stamp)).isNull()
    }

    @Test
    fun `a miss leaves the stale entry in place until the file is remembered again`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)

        assertThat(index.idFor(uri, stamp.copy(sizeBytes = 1))).isNull()

        assertThat(index.idFor(uri, stamp)).isEqualTo(audioId(1))
    }

    @Test
    fun `uris are independent`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)
        index.remember("content://other", audioId(2), stamp)

        assertThat(index.idFor(uri, stamp)).isEqualTo(audioId(1))
        assertThat(index.idFor("content://other", stamp)).isEqualTo(audioId(2))
    }

    @Test
    fun `without a stamp the remembered id is returned as it is`() = runBlocking<Unit> {
        index.remember(uri, audioId(1), stamp)

        assertThat(index.idFor(uri)).isEqualTo(audioId(1))
    }

    @Test
    fun `an id remembered without a stamp cannot vouch for a file that has one`() = runBlocking<Unit> {
        index.remember(uri, audioId(1))

        assertThat(index.idFor(uri)).isEqualTo(audioId(1))
        assertThat(index.idFor(uri, stamp)).isNull()
    }

    @Test
    fun `a row whose id cannot be read is a miss`() = runBlocking<Unit> {
        db.audioIdDao().upsert(io.sonora.core.djstore.db.AudioIdEntity(uri, stamp.sizeBytes, stamp.lastModifiedMs, "not an id"))

        assertThat(index.idFor(uri, stamp)).isNull()
    }
}
