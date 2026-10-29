package io.sonora.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sort store against a real preferences DataStore file of its own, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreSortPreferencesRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "sonora_sort.preferences_pb") }

    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStoreSortPreferencesRepository) -> T): T =
        withStore { block(DataStoreSortPreferencesRepository(it)) }

    private val songs = SortTarget(SortList.SONGS)

    @Test
    fun `nothing stored reads as never chosen`() {
        assertThat(withRepository { it.order(songs).first() }).isNull()
    }

    @Test
    fun `each list's order survives reopening the store`() {
        withRepository {
            it.setOrder(songs, SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING))
            it.setOrder(SortTarget(SortList.ALBUMS), SortOrder(SortKey.ARTIST, SortDirection.ASCENDING))
        }

        withRepository {
            assertThat(it.order(songs).first()).isEqualTo(SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING))
            assertThat(it.order(SortTarget(SortList.ALBUMS)).first()).isEqualTo(SortOrder(SortKey.ARTIST, SortDirection.ASCENDING))
            assertThat(it.order(SortTarget(SortList.ARTISTS)).first()).isNull()
        }
    }

    @Test
    fun `playlists keep one order each and a cleared one reads as never chosen`() {
        val first = SortTarget(SortList.PLAYLIST, playlistId = 1)
        val second = SortTarget(SortList.PLAYLIST, playlistId = 2)

        withRepository {
            it.setOrder(first, SortOrder(SortKey.TITLE, SortDirection.DESCENDING))
            it.setOrder(second, SortOrder(SortKey.DURATION, SortDirection.ASCENDING))
            it.clear(first)

            assertThat(it.order(first).first()).isNull()
            assertThat(it.order(second).first()).isEqualTo(SortOrder(SortKey.DURATION, SortDirection.ASCENDING))
        }
    }

    @Test
    fun `stored names are the enum names under one key per list`() {
        withRepository { it.setOrder(songs, SortOrder(SortKey.GENRE, SortDirection.DESCENDING)) }

        val stored = withStore { store -> store.data.first().asMap().mapKeys { it.key.name } }

        assertThat(stored).containsExactly("sort.songs.key", "GENRE", "sort.songs.direction", "DESCENDING")
    }

    @Test
    fun `an unknown stored name reads as never chosen`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("sort.songs.key")] = "MOOD"
                it[stringPreferencesKey("sort.songs.direction")] = "DESCENDING"
            }
        }

        assertThat(withRepository { it.order(songs).first() }).isNull()
    }

    @Test
    fun `a corrupt file reads as never chosen and can be written again`() {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))

        withRepository {
            assertThat(it.order(songs).first()).isNull()
            it.setOrder(songs, SortOrder(SortKey.ARTIST))
            assertThat(it.order(songs).first()).isEqualTo(SortOrder(SortKey.ARTIST))
        }
    }
}
