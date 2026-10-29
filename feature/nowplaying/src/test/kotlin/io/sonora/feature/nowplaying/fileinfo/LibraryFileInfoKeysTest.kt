package io.sonora.feature.nowplaying.fileinfo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Each File Info call site keeps its own panel and editor in a shared ViewModelStoreOwner. */
class LibraryFileInfoKeysTest {

    @Test
    fun `two call sites never share a view model`() {
        val library = LibraryFileInfoKeys.at(callSite = 1_234_567L)
        val search = LibraryFileInfoKeys.at(callSite = -98_765L)

        assertThat(listOf(library.fileInfo, library.tagEditor, search.fileInfo, search.tagEditor)).containsNoDuplicates()
    }

    @Test
    fun `one call site keeps its keys`() {
        assertThat(LibraryFileInfoKeys.at(42L)).isEqualTo(LibraryFileInfoKeys.at(42L))
    }
}
