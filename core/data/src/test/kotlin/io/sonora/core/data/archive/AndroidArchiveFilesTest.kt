package io.sonora.core.data.archive

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.CompressedArchive
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Saving a finished archive where the user picked, with the documents as `file://` URIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidArchiveFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files = AndroidArchiveFiles(RuntimeEnvironment.getApplication())

    private fun archive(bytes: ByteArray): CompressedArchive {
        val file = File(temp.root, "Mix.zip").apply { writeBytes(bytes) }
        return CompressedArchive(file.path, file.name, bytes.size.toLong(), 1, encrypted = true)
    }

    @Test
    fun `Save copies every byte into the picked document`() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val target = temp.newFile("saved.zip")

        val saved = files.saveTo(archive(bytes), Uri.fromFile(target).toString())

        assertThat(saved).isTrue()
        assertThat(target.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `an archive gone from the cache is not saved`() = runBlocking {
        val gone = CompressedArchive(File(temp.root, "gone.zip").path, "gone.zip", 10, 1, encrypted = false)

        assertThat(files.saveTo(gone, Uri.fromFile(temp.newFile("saved.zip")).toString())).isFalse()
    }

    @Test
    fun `a document that cannot be written is not saved`() = runBlocking {
        val folder = temp.newFolder("not-a-file")

        assertThat(files.saveTo(archive(byteArrayOf(1, 2, 3)), Uri.fromFile(folder).toString())).isFalse()
    }
}
