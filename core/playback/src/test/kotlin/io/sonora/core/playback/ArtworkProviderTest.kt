package io.sonora.core.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Artwork served to Android Auto, which can read neither the app's cache files nor its folder grants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArtworkProviderTest {

    /** A folder the user picked, as the documents provider serves its files to Sonora. */
    class FakeDocuments : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(context!!.filesDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    private val context = RuntimeEnvironment.getApplication()
    private val artwork = CarArtwork(context)
    private lateinit var provider: ArtworkProvider
    private val cover = File(context.cacheDir, "artwork/0123abcd.jpg")

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(ArtworkProvider::class.java).create(CarArtwork.authority(context)).get()
        Robolectric.buildContentProvider(FakeDocuments::class.java).create("io.sonora.test.documents")
        cover.parentFile!!.mkdirs()
        cover.writeBytes(byteArrayOf(1, 2, 3))
        File(context.filesDir, "folder.jpg").writeBytes(byteArrayOf(7, 8))
        File(context.filesDir, "secret.db").writeBytes(byteArrayOf(9))
    }

    private fun read(uri: String): ByteArray =
        provider.openFile(uri.toUri(), "r")!!.use { FileInputStream(it.fileDescriptor).readBytes() }

    @Test
    fun `a cached cover is served under an opaque key`() {
        val uri = artwork.carUri(cover.toUri().toString())!!

        assertThat(uri).isEqualTo("content://${context.packageName}.artwork/c_0123abcd.jpg")
        assertThat(read(uri)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a folder image is served through Sonora's own access, under a key that hides its location`() {
        val document = "content://io.sonora.test.documents/tree/music/document/folder.jpg"

        val uri = artwork.carUri(document)!!

        assertThat(uri).startsWith("content://${context.packageName}.artwork/s_")
        assertThat(uri).doesNotContain("folder")
        assertThat(read(uri)).isEqualTo(byteArrayOf(7, 8))
    }

    @Test
    fun `files outside the artwork cache are never served`() {
        assertThat(artwork.carUri(File(context.filesDir, "secret.db").toUri().toString())).isNull()
        assertThat(artwork.carUri("file:///data/data/other/cache/artwork/x.jpg")).isNull()
        val base = "content://${context.packageName}.artwork/"
        listOf("c_..%2Ffiles%2Fsecret.db", "c_..", "c_", "x_0123abcd.jpg", "c_0123abcd.jpg%2F..%2F..%2Ffiles%2Fsecret.db")
            .forEach { key -> assertThrows(FileNotFoundException::class.java) { read(base + key) } }
    }

    @Test
    fun `unknown and missing keys are not found`() {
        val base = "content://${context.packageName}.artwork/"

        assertThrows(FileNotFoundException::class.java) { read(base + "s_00ff") }
        assertThrows(FileNotFoundException::class.java) { read(base + "c_missing.jpg") }
    }

    @Test
    fun `the provider is read-only`() {
        val uri = artwork.carUri(cover.toUri().toString())!!.toUri()

        assertThrows(SecurityException::class.java) { provider.openFile(uri, "w") }
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "rw") }
        assertThat(provider.delete(uri, null, null)).isEqualTo(0)
        assertThat(cover.exists()).isTrue()
    }
}
