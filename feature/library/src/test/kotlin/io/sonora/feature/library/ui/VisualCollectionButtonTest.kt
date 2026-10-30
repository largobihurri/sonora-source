package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.feature.library.LibraryDest
import io.sonora.feature.library.hasVisualCollectionButton
import org.junit.Test

class VisualCollectionButtonTest {

    private val pad = WindowClass.of(1152, 720)
    private val upright = WindowClass.of(720, 1152)

    @Test
    fun `Albums, Artists and Playlists offer the visual collection button on a large screen`() {
        for (dest in listOf(LibraryDest.Albums, LibraryDest.Artists, LibraryDest.Playlists)) {
            assertThat(dest.hasVisualCollectionButton(pad)).isTrue()
            assertThat(dest.hasVisualCollectionButton(upright)).isTrue()
        }
    }

    @Test
    fun `a phone never gets the button, its visual collection is the sideways turn`() {
        for (dest in listOf(LibraryDest.Albums, LibraryDest.Artists, LibraryDest.Playlists)) {
            assertThat(dest.hasVisualCollectionButton(WindowClass.Phone)).isFalse()
            assertThat(dest.hasVisualCollectionButton(WindowClass.of(704, 320))).isFalse()
        }
    }

    @Test
    fun `other pages have no button`() {
        for (dest in listOf(LibraryDest.Home, LibraryDest.Songs, LibraryDest.Folders, LibraryDest.Converted, LibraryDest.Statistics)) {
            assertThat(dest.hasVisualCollectionButton(pad)).isFalse()
        }
        assertThat(LibraryDest.AlbumDetail(1).hasVisualCollectionButton(pad)).isFalse()
    }
}
