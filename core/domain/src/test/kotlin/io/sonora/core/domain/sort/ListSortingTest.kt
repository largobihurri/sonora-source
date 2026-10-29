package io.sonora.core.domain.sort

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SortPreferencesRepository
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class ListSortingTest {

    private class FakeSortStore : SortPreferencesRepository {
        val stored = MutableStateFlow<Map<SortTarget, SortOrder>>(emptyMap())

        override fun order(target: SortTarget): Flow<SortOrder?> = stored.map { it[target] }

        override suspend fun setOrder(target: SortTarget, order: SortOrder) {
            stored.value += target to order
        }

        override suspend fun clear(target: SortTarget) {
            stored.value -= target
        }
    }

    private class FakePreferences(language: AppLanguage) : PreferencesRepository {
        val state = MutableStateFlow(AppPreferences(language = language))
        override val preferences: Flow<AppPreferences> = state

        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            state.value = transform(state.value)
        }
    }

    private val store = FakeSortStore()
    private val preferences = FakePreferences(AppLanguage.SPANISH)

    @Test
    fun `each list has its keys and a default`() {
        assertThat(SortList.SONGS.keys).containsExactly(
            SortKey.TITLE, SortKey.ARTIST, SortKey.ALBUM, SortKey.GENRE, SortKey.RELEASE_DATE, SortKey.DATE_ADDED, SortKey.DURATION,
        ).inOrder()
        assertThat(SortList.ALBUMS.keys).containsExactly(SortKey.TITLE, SortKey.ARTIST, SortKey.RELEASE_DATE, SortKey.DATE_ADDED).inOrder()
        assertThat(SortList.ARTISTS.keys).containsExactly(SortKey.NAME, SortKey.DATE_ADDED).inOrder()
        assertThat(SortList.FOLDER.keys).containsExactly(SortKey.NAME, SortKey.ARTIST, SortKey.DATE_ADDED, SortKey.DURATION).inOrder()
        assertThat(SortList.CONVERTED.keys.first()).isEqualTo(SortKey.DATE_CONVERTED)
        assertThat(SortList.PLAYLIST.keys.first()).isEqualTo(SortKey.PLAYLIST_ORDER)

        assertThat(SortList.SONGS.default).isEqualTo(SortOrder(SortKey.TITLE, SortDirection.ASCENDING))
        assertThat(SortList.CONVERTED.default).isEqualTo(SortOrder(SortKey.DATE_CONVERTED, SortDirection.DESCENDING))
        assertThat(SortList.PLAYLIST.default).isEqualTo(SortOrder(SortKey.PLAYLIST_ORDER, SortDirection.ASCENDING))
    }

    @Test
    fun `picking another key starts in that key's natural direction, picking a direction keeps the key`() {
        val byTitle = SortOrder(SortKey.TITLE, SortDirection.DESCENDING)

        assertThat(byTitle.withKey(SortKey.DATE_ADDED)).isEqualTo(SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING))
        assertThat(byTitle.withKey(SortKey.ARTIST)).isEqualTo(SortOrder(SortKey.ARTIST, SortDirection.ASCENDING))
        assertThat(byTitle.withKey(SortKey.TITLE)).isEqualTo(byTitle)
        assertThat(byTitle.withDirection(SortDirection.ASCENDING)).isEqualTo(SortOrder(SortKey.TITLE, SortDirection.ASCENDING))
        assertThat(SortKey.TITLE.isAlphabetical).isTrue()
        assertThat(SortKey.DURATION.isAlphabetical).isFalse()
    }

    @Test
    fun `playlist order has no direction`() {
        assertThat(SortList.PLAYLIST.valid(SortOrder(SortKey.PLAYLIST_ORDER, SortDirection.DESCENDING)))
            .isEqualTo(SortOrder(SortKey.PLAYLIST_ORDER, SortDirection.ASCENDING))
        assertThat(SortKey.PLAYLIST_ORDER.hasDirection).isFalse()
    }

    @Test
    fun `nothing stored, or a key the list does not offer, reads as the list's default`() = runBlocking {
        val sorting = ListSorting(store, preferences)
        val artists = SortTarget(SortList.ARTISTS)

        assertThat(sorting.order(artists).first()).isEqualTo(SortList.ARTISTS.default)

        store.setOrder(artists, SortOrder(SortKey.DURATION, SortDirection.DESCENDING))
        assertThat(sorting.order(artists).first()).isEqualTo(SortList.ARTISTS.default)
    }

    @Test
    fun `selecting stores the order for that list only`() = runBlocking {
        val sorting = ListSorting(store, preferences)
        val first = SortTarget(SortList.PLAYLIST, playlistId = 1)
        val second = SortTarget(SortList.PLAYLIST, playlistId = 2)

        sorting.select(first, SortOrder(SortKey.ARTIST, SortDirection.DESCENDING))

        assertThat(sorting.order(first).first()).isEqualTo(SortOrder(SortKey.ARTIST, SortDirection.DESCENDING))
        assertThat(sorting.order(second).first()).isEqualTo(SortList.PLAYLIST.default)

        sorting.forget(first)
        assertThat(sorting.order(first).first()).isEqualTo(SortList.PLAYLIST.default)
    }

    @Test
    fun `sorted applies the stored order in the app language and follows changes`() = runBlocking {
        val sorting = ListSorting(store, preferences, Dispatchers.Unconfined)
        val target = SortTarget(SortList.SONGS)
        val calls = mutableListOf<Pair<SortOrder, Locale>>()

        val sorted = sorting.sorted(flowOf(listOf("b", "a")), target) { items, order, locale ->
            calls += order to locale
            if (order.direction == SortDirection.ASCENDING) items.sorted() else items.sortedDescending()
        }

        assertThat(sorted.first()).isEqualTo(Sorted(SortList.SONGS.default, listOf("a", "b")))
        assertThat(calls.last().second.language).isEqualTo("es")

        store.setOrder(target, SortOrder(SortKey.TITLE, SortDirection.DESCENDING))
        preferences.update { it.copy(language = AppLanguage.BASQUE) }
        assertThat(sorted.first().items).containsExactly("b", "a").inOrder()
        assertThat(calls.last().second.language).isEqualTo("eu")
    }
}
