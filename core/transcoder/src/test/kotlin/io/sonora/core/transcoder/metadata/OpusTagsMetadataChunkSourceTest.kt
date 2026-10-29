package io.sonora.core.transcoder.metadata

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.port.SourceMetadataReader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OpusTagsMetadataChunkSourceTest {

    private val watch = ConversionProfiles.WATCH_OPUS
    private val scaler = CoverScaler { _, _ -> null }

    private fun source(read: suspend (String) -> TrackMetadata?) = OpusTagsMetadataChunkSource(SourceMetadataReader { read(it) }, scaler)

    @Test
    fun `tags become one OpusTags chunk with Sonora as the vendor`() = runTest {
        val chunks = source { TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria")) }.chunksFor("uri", watch)

        val chunk = chunks.single()
        assertThat(chunk.id).isEqualTo("OTAG")
        assertThat(String(chunk.data, 0, 8)).isEqualTo("OpusTags")
        val text = String(chunk.data, Charsets.UTF_8)
        assertThat(text).contains("Sonora (${watch.displayName})")
        assertThat(text).contains("TITLE=Aria")
    }

    @Test
    fun `a source without tags or with unreadable tags gets no chunk`() = runTest {
        assertThat(source { null }.chunksFor("uri", watch)).isEmpty()
        assertThat(source { TrackMetadata() }.chunksFor("uri", watch)).isEmpty()
        assertThat(source { error("damaged") }.chunksFor("uri", watch)).isEmpty()
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val result = runCatching { source { throw CancellationException("stop") }.chunksFor("uri", watch) }

        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
