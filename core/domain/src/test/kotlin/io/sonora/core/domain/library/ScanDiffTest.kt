package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanDiffTest {

    private fun file(name: String, size: Long = 100, modified: Long = 1_000) = FileIdentity("content://doc/$name", size, modified)

    @Test
    fun `classifies added, changed, unchanged and removed files`() {
        val indexed = listOf(file("same"), file("resized"), file("touched"), file("gone"))
        val current = listOf(file("same"), file("resized", size = 200), file("touched", modified = 2_000), file("new"))

        val diff = ScanDiff.compute(indexed, current)

        assertThat(diff.added).containsExactly(file("new"))
        assertThat(diff.changed).containsExactly(file("resized", size = 200), file("touched", modified = 2_000)).inOrder()
        assertThat(diff.unchanged).containsExactly(file("same"))
        assertThat(diff.removed).containsExactly(file("gone"))
    }

    @Test
    fun `first scan adds everything and an empty listing removes everything`() {
        val files = listOf(file("a"), file("b"))
        assertThat(ScanDiff.compute(emptyList(), files).added).containsExactlyElementsIn(files).inOrder()
        val cleared = ScanDiff.compute(files, emptyList())
        assertThat(cleared.removed).containsExactlyElementsIn(files)
        assertThat(cleared.added + cleared.changed + cleared.unchanged).isEmpty()
    }

    @Test
    fun `a file listed twice is counted once`() {
        val diff = ScanDiff.compute(emptyList(), listOf(file("a"), file("a", size = 5)))
        assertThat(diff.added).containsExactly(file("a", size = 5))
    }
}
