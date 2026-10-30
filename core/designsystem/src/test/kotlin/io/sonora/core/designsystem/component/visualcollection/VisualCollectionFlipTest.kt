package io.sonora.core.designsystem.component.visualcollection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The flipped cover is followed by its key, so a list change never turns another item's back over. */
class VisualCollectionFlipTest {

    private fun item(key: Long) = VisualCollectionItem(key, covers = emptyList(), title = "Album $key", subtitle = "")

    @Test
    fun `the flipped cover is found by its key after an item is inserted before it`() {
        val before = listOf(item(1), item(2), item(3))
        val flipped = FlippedCover(before[1].key)
        assertThat(flipped.indexIn(before)).isEqualTo(1)

        val after = listOf(item(0), item(1), item(2), item(3))
        assertThat(flipped.indexIn(after)).isEqualTo(2)
        assertThat(after[flipped.indexIn(after)!!].title).isEqualTo("Album 2")
    }

    @Test
    fun `a flipped cover that left the list is gone`() {
        val flipped = FlippedCover(7L)
        assertThat(flipped.indexIn(listOf(item(1), item(2)))).isNull()
        assertThat(flipped.indexIn(emptyList<VisualCollectionItem<Long>>())).isNull()
    }

    @Test
    fun `a null key is a key like any other`() {
        val items = listOf(VisualCollectionItem<String?>(null, emptyList(), "Unknown", ""), VisualCollectionItem<String?>("a", emptyList(), "A", ""))
        assertThat(FlippedCover(null).indexIn(items)).isEqualTo(0)
    }
}
