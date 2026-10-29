package io.sonora.core.data.conversion

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract.Document
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.saf.ListedAudioFile
import io.sonora.core.data.library.tags.TagReadResult
import io.sonora.core.data.library.tags.TagReader
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.port.ConvertedFile
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** How a converter output is opened to play it: its document details from SAF, its tags from the library's reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafConvertedFileReaderTest {

    private val context = RuntimeEnvironment.getApplication()
    private val uri = "content://$AUTHORITY/tree/out/document/song"
    private val tags = TrackTags(title = "Better Will Come", artist = "Atili", durationMs = 200_000)

    private val listed = mutableListOf<ListedAudioFile>()
    private var tagResult: TagReadResult? = TagReadResult(tags, "file:///cache/artwork/a.jpg")
    private val reader = SafConvertedFileReader(context, TagReader { file -> listed += file; tagResult })

    @Before
    fun setUp() {
        DocumentProvider.mode = "row"
        DocumentProvider.row = arrayOf("Song.aiff", "audio/x-aiff", 1_234L, 5_000L)
        Robolectric.buildContentProvider(DocumentProvider::class.java).create(ProviderInfo().apply { authority = AUTHORITY })
    }

    private fun read(): ConvertedFile? = runBlocking { reader.read(uri, "Fallback.aiff") }

    @Test
    fun `a converted file is read with the library's tag reader`() {
        val file = read()

        assertThat(file).isEqualTo(ConvertedFile(tags, "file:///cache/artwork/a.jpg", "audio/x-aiff", 1_234))
        val asked = listed.single()
        assertThat(asked.documentUri).isEqualTo(uri)
        assertThat(asked.fileName).isEqualTo("Song.aiff")
        assertThat(asked.mimeType).isEqualTo("audio/x-aiff")
    }

    @Test
    fun `missing name and type fall back to the recorded name and a generic audio type`() {
        DocumentProvider.row = arrayOf(null, null, null, null)

        val file = read()

        assertThat(file?.mimeType).isEqualTo("audio/*")
        assertThat(file?.sizeBytes).isEqualTo(0)
        assertThat(listed.single().fileName).isEqualTo("Fallback.aiff")
    }

    @Test
    fun `untagged files still play`() {
        tagResult = TagReadResult.UNTAGGED

        assertThat(read()).isEqualTo(ConvertedFile(TrackTags.UNTAGGED, null, "audio/x-aiff", 1_234))
    }

    @Test
    fun `a document that is gone is unavailable`() {
        DocumentProvider.mode = "empty"
        assertThat(read()).isNull()

        DocumentProvider.mode = "missing"
        assertThat(read()).isNull()

        DocumentProvider.mode = "null"
        assertThat(read()).isNull()
        assertThat(listed).isEmpty()
    }

    @Test
    fun `a document whose grant was revoked is unavailable`() {
        DocumentProvider.mode = "security"

        assertThat(read()).isNull()
    }

    @Test
    fun `a file the tag reader cannot open is unavailable`() {
        tagResult = null

        assertThat(read()).isNull()
    }

    @Test
    fun `a tag reader that breaks makes the file unavailable instead of crashing`() = runBlocking<Unit> {
        val broken = SafConvertedFileReader(context, TagReader { throw IllegalStateException("reader died") })

        assertThat(broken.read(uri, "Song.aiff")).isNull()
    }

    @Test
    fun `a URI that is not a document is unavailable`() = runBlocking<Unit> {
        assertThat(reader.read("not a uri", "x.aiff")).isNull()
    }

    class DocumentProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
            when (mode) {
                "security" -> throw SecurityException("revoked")
                "missing" -> throw IllegalArgumentException(FileNotFoundException("Missing file for $uri"))
                "null" -> null
                else -> MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)).apply {
                    if (mode == "row") addRow(row)
                }
            }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            var mode = "row"
            var row: Array<Any?> = emptyArray()
        }
    }

    private companion object {
        const val AUTHORITY = "io.sonora.test.converted"
    }
}
