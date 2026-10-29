package io.sonora.core.data.library.duplicates.db

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.duplicates.trackRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DuplicateDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.duplicateDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `every indexed file is listed, hidden songs included`() = runTest {
        db.libraryDao().upsertTracks(listOf(trackRow("a"), trackRow("b")))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO hidden_items (kind, item_key, title, subtitle, hidden_at_ms) VALUES ('SONG', 'content://tree/music/document/b', 'b', '', 1)",
        )
        assertThat(dao.files().map { it.fileName }).containsExactly("a.mp3", "b.mp3")
        assertThat(dao.fileUris()).hasSize(2)
    }

    @Test
    fun `a dismissal is kept once per file`() = runTest {
        dao.insertDismissals(listOf(DuplicateDismissalEntity(1, "a"), DuplicateDismissalEntity(1, "b")))
        dao.insertDismissals(listOf(DuplicateDismissalEntity(1, "a")))
        assertThat(dao.dismissals()).hasSize(2)
    }

    @Test
    fun `many fingerprints go in and out in chunks`() = runTest {
        val rows = (1..2_000).map { DuplicateFingerprintEntity("f$it", 1, 2, null, "k", 1) }
        dao.updateFingerprints(rows, gone = emptyList())
        dao.updateFingerprints(emptyList(), gone = rows.take(1_500).map { it.documentUri })
        assertThat(dao.fingerprints()).hasSize(500)
    }

    @Test
    fun `settings are edited from their defaults and observed`() = runTest {
        assertThat(dao.settings().first()).isNull()
        dao.editSettings { it.copy(notify = false) }
        assertThat(dao.settings().first()).isEqualTo(DuplicateSettingsEntity(notify = false))
        dao.editSettings { it.copy(lastNotifiedCount = 3) }
        assertThat(dao.getSettings()).isEqualTo(DuplicateSettingsEntity(notify = false, lastNotifiedCount = 3))
    }
}
