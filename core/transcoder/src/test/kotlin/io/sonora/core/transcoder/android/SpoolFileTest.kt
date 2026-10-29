package io.sonora.core.transcoder.android

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpoolFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a spool file that cannot be opened is deleted`() {
        val cache = folder.newFolder("cache")

        assertThrows(IOException::class.java) { openSpoolFile(cache) { throw IOException("disk full") } }

        assertThat(cache.listFiles()).isEmpty()
    }

    @Test
    fun `an opened spool file is writable and lives in the cache directory`() {
        val cache = folder.newFolder("cache")

        val (file, access) = openSpoolFile(cache)
        access.use { it.write(byteArrayOf(1, 2, 3)) }

        assertThat(file.parentFile).isEqualTo(cache)
        assertThat(file.name).endsWith(".part")
        assertThat(file.length()).isEqualTo(3)
    }
}
