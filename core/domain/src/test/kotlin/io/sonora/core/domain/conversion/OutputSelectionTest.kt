package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutputSelectionTest {

    private val empty = OutputSelection("aiff")

    @Test
    fun `every source and child follows the batch-wide value until set by hand`() {
        assertThat(empty.resolve("song")).isEqualTo("aiff")
        assertThat(empty.resolve("folder", "folder/a")).isEqualTo("aiff")
        assertThat(empty.isManual("song")).isFalse()
        assertThat(empty.isManual("folder", "folder/a")).isFalse()
    }

    @Test
    fun `changing the batch-wide value moves every entry still on it and keeps those set by hand`() {
        val selection = empty
            .withSource("manual song", "flac")
            .withChild("folder", "folder/manual", "mp3")
            .withBatchWide("wav")

        assertThat(selection.batchWide).isEqualTo("wav")
        assertThat(selection.resolve("plain song")).isEqualTo("wav")
        assertThat(selection.resolve("folder", "folder/plain")).isEqualTo("wav")
        assertThat(selection.resolve("manual song")).isEqualTo("flac")
        assertThat(selection.resolve("folder", "folder/manual")).isEqualTo("mp3")
        assertThat(selection.isManual("manual song")).isTrue()
        assertThat(selection.isManual("folder", "folder/manual")).isTrue()
        assertThat(selection.isManual("plain song")).isFalse()
    }

    @Test
    fun `a value set by hand stays manual even when it equals the batch-wide one`() {
        val selection = empty.withSource("song", "aiff").withBatchWide("flac")

        assertThat(selection.resolve("song")).isEqualTo("aiff")
        assertThat(selection.isManual("song")).isTrue()
    }

    @Test
    fun `a folder's value reaches its children, and a child set by hand wins over it`() {
        val selection = empty
            .withSource("folder", "flac")
            .withChild("folder", "folder/b", "mp3")

        assertThat(selection.resolve("folder")).isEqualTo("flac")
        assertThat(selection.resolve("folder", "folder/a")).isEqualTo("flac")
        assertThat(selection.resolve("folder", "folder/b")).isEqualTo("mp3")
        // A child on its folder's value carries no mark of its own.
        assertThat(selection.isManual("folder", "folder/a")).isFalse()
        assertThat(selection.isManual("folder", "folder/b")).isTrue()
        assertThat(selection.isManual("folder")).isTrue()
    }

    @Test
    fun `changing a folder's value moves its children still on it and keeps those set by hand`() {
        val selection = empty
            .withSource("folder", "flac")
            .withChild("folder", "folder/b", "mp3")
            .withSource("folder", "wav")

        assertThat(selection.resolve("folder", "folder/a")).isEqualTo("wav")
        assertThat(selection.resolve("folder", "folder/b")).isEqualTo("mp3")
    }

    @Test
    fun `clearing an entry puts it back on the value above it`() {
        val set = empty
            .withSource("song", "flac")
            .withSource("folder", "wav")
            .withChild("folder", "folder/b", "mp3")

        val cleared = set.clearSource("song").clearChild("folder", "folder/b")

        assertThat(cleared.resolve("song")).isEqualTo("aiff")
        assertThat(cleared.isManual("song")).isFalse()
        assertThat(cleared.resolve("folder", "folder/b")).isEqualTo("wav")
        assertThat(cleared.isManual("folder", "folder/b")).isFalse()
        // Clearing the folder keeps the children set by hand.
        val folderCleared = set.clearSource("folder")
        assertThat(folderCleared.resolve("folder", "folder/a")).isEqualTo("aiff")
        assertThat(folderCleared.resolve("folder", "folder/b")).isEqualTo("mp3")
        assertThat(folderCleared.isManual("folder")).isFalse()
    }

    @Test
    fun `removing a source drops its own and its children's values`() {
        val selection = empty
            .withSource("folder", "wav")
            .withChild("folder", "folder/b", "mp3")
            .withSource("song", "flac")
            .removeSource("folder")

        assertThat(selection.resolve("folder")).isEqualTo("aiff")
        assertThat(selection.resolve("folder", "folder/b")).isEqualTo("aiff")
        assertThat(selection.isManual("folder", "folder/b")).isFalse()
        assertThat(selection.resolve("song")).isEqualTo("flac")
    }

    @Test
    fun `a folder counts the songs inside it set by hand`() {
        val selection = empty
            .withChild("folder", "folder/a", "mp3")
            .withChild("folder", "folder/b", "flac")
            .withChild("folder", "folder/a", "wav") // set again: still one song
            .withSource("folder", "opus") // the folder's own value is not one of its songs
            .withChild("other", "other/c", "mp3")

        assertThat(selection.manualChildCount("folder")).isEqualTo(2)
        assertThat(selection.clearChild("folder", "folder/b").manualChildCount("folder")).isEqualTo(1)
        assertThat(selection.manualChildCount("song")).isEqualTo(0)
    }

    @Test
    fun `a child named like its source resolves as the source itself`() {
        val selection = empty.withSource("song", "flac")

        assertThat(selection.resolve("song", "song")).isEqualTo("flac")
        assertThat(selection.isManual("song", "song")).isTrue()
    }
}
