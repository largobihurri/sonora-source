package io.sonora.core.data.library.duplicates

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafContentHasherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hasher = SafContentHasher(RuntimeEnvironment.getApplication())

    @Test
    fun `a stream is hashed to its SHA-256, read in chunks`() = runTest {
        assertThat(SafContentHasher.sha256(ByteArrayInputStream("abc".toByteArray())))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertThat(SafContentHasher.sha256(ByteArrayInputStream(big)))
            .isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(big).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `equal files hash alike, a missing one cannot be read`() = runTest {
        val a = File(folder.root, "a.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = File(folder.root, "b.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(hasher.hash(Uri.fromFile(a).toString())).isNotNull()
        assertThat(hasher.hash(Uri.fromFile(a).toString())).isEqualTo(hasher.hash(Uri.fromFile(b).toString()))
        assertThat(hasher.hash(Uri.fromFile(File(folder.root, "gone.flac")).toString())).isNull()
    }
}
