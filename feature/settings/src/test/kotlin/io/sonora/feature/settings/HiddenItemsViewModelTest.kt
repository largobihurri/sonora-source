package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiddenItemsViewModelTest {

    /** Only the hidden list matters here; restoring drops the item from it. */
    private class FakeMarks(items: List<HiddenItem>) : LibraryMarksRepository {
        val hiddenItems = MutableStateFlow(items)
        val restored = mutableListOf<ItemRef>()
        override fun favourites(kind: MarkKind): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) = Unit
        override fun pins(context: PinContext): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) = Unit
        override fun hidden(): Flow<List<HiddenItem>> = hiddenItems
        override suspend fun hide(item: HiddenItem) = Unit
        override suspend fun restore(ref: ItemRef) {
            restored += ref
            hiddenItems.value = hiddenItems.value.filterNot { it.ref == ref }
        }
        override suspend fun forgetSongs(uris: Collection<String>) = Unit
    }

    private val song = HiddenItem(ItemRef.song("content://music/1"), "Halo", "Beyoncé", hiddenAtMs = 20)
    private val album = HiddenItem(ItemRef.album(7L), "Renaissance", "Beyoncé", hiddenAtMs = 10)
    private val marks = FakeMarks(listOf(song, album))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lists the hidden items as the repository orders them`() = runTest {
        val viewModel = HiddenItemsViewModel(marks)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.items.collect {} }

        assertThat(viewModel.items.value).containsExactly(song, album).inOrder()
    }

    @Test
    fun `restoring an item shows it in the library again and drops it from the list`() = runTest {
        val viewModel = HiddenItemsViewModel(marks)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.items.collect {} }

        viewModel.restore(song)

        assertThat(marks.restored).containsExactly(song.ref)
        assertThat(viewModel.items.value).containsExactly(album)
    }
}
