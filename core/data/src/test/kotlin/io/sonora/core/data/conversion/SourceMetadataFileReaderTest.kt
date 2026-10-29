package io.sonora.core.data.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The source tag read for a conversion: temporary copies never outlive the read, and every failure is "no tags". */
class SourceMetadataFileReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val tempDir by lazy { folder.newFolder("cache") }
    private val content = byteArrayOf(1, 2, 3, 4)
    private val tagged = TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Song"))
    private val cover = CoverArt(byteArrayOf(9), "image/jpeg", 1, 1)

    private fun reader(readTags: (File, String) -> Pair<TrackMetadata, ByteArray?>?) =
        SourceMetadataFileReader(tempDir, prepareCover = { cover }, readTags = readTags)

    private fun readCopy(readTags: (File, String) -> Pair<TrackMetadata, ByteArray?>?) =
        reader(readTags).read("aiff", direct = null) { out -> out.write(content) }

    @Test
    fun `a copied source is read from its copy, which is then deleted`() {
        var copied: ByteArray? = null

        val metadata = readCopy { file, readerKey ->
            assertThat(readerKey).isEqualTo("aiff")
            copied = file.readBytes()
            tagged to byteArrayOf(7)
        }

        assertThat(metadata).isEqualTo(tagged.copy(cover = cover))
        assertThat(copied).isEqualTo(content)
        assertThat(tempDir.listFiles()).isEmpty()
    }

    @Test
    fun `a copy that breaks part way is deleted and gives no tags`() {
        val metadata = reader { _, _ -> error("not read") }.read("aiff", direct = null) { out ->
            out.write(content)
            throw IOException("provider went away")
        }

        assertThat(metadata).isNull()
        assertThat(tempDir.listFiles()).isEmpty()
    }

    @Test
    fun `a tag read that runs out of memory gives no tags and deletes the copy`() {
        val metadata = readCopy { _, _ -> throw OutOfMemoryError("embedded picture of 2 GB") }

        assertThat(metadata).isNull()
        assertThat(tempDir.listFiles()).isEmpty()
    }

    @Test
    fun `unreadable tags or a missing tag library give no tags`() {
        assertThat(readCopy { _, _ -> throw IllegalStateException("corrupt frame") }).isNull()
        assertThat(readCopy { _, _ -> throw NoClassDefFoundError("org/jaudiotagger/audio/AudioFileIO") }).isNull()
        assertThat(tempDir.listFiles()).isEmpty()
    }

    @Test
    fun `a cancelled read still cancels and deletes the copy`() {
        assertThrows(CancellationException::class.java) { readCopy { _, _ -> throw CancellationException("stopped") } }
        assertThat(tempDir.listFiles()).isEmpty()
    }

    @Test
    fun `a readable path is read in place without a copy`() {
        val direct = folder.newFile("song.aiff")

        val metadata = reader { file, _ -> tagged.takeIf { file == direct }?.let { it to null } }
            .read("aiff", direct) { error("not copied") }

        assertThat(metadata).isEqualTo(tagged)
        assertThat(tempDir.listFiles()).isEmpty()
    }
}
