package io.sonora.core.decoder

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.decoder.di.DecoderDispatcher
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [PcmDecoder] for document URIs (SAF `content://`, also `file://`). AIFF and AIFF-C, which the platform cannot
 * demux, are recognised by their header and read with [AiffPcmReader]; everything else goes to
 * [MediaCodecPcmReader]. All blocking work runs on the injected dispatcher.
 */
class AndroidPcmDecoder @Inject constructor(
    @ApplicationContext context: Context,
    @DecoderDispatcher private val dispatcher: CoroutineDispatcher,
) : PcmDecoder {

    private val resolver = context.contentResolver

    override suspend fun open(sourceUri: String): PcmStream = openOn(dispatcher) {
        if (startsAsAiff(sourceUri)) {
            AiffPcmReader.open(sourceUri) { openInput(sourceUri) }
        } else {
            MediaCodecPcmReader.open(resolver, sourceUri)
        }
    }

    private fun startsAsAiff(sourceUri: String): Boolean {
        val prefix = ByteArray(AIFF_PREFIX_BYTES)
        val length = try {
            openInput(sourceUri).use { input ->
                var done = 0
                while (done < prefix.size) {
                    val read = input.read(prefix, done, prefix.size - done)
                    if (read < 0) break
                    done += read
                }
                done
            }
        } catch (e: IOException) {
            throw PcmDecodeException.SourceUnavailable(sourceUri, e)
        } catch (e: SecurityException) {
            throw PcmDecodeException.SourceUnavailable(sourceUri, e)
        }
        return length == prefix.size && AiffParser.isAiff(prefix)
    }

    private fun openInput(sourceUri: String): InputStream = try {
        resolver.openInputStream(sourceUri.toUri())
    } catch (e: IllegalArgumentException) {
        throw IOException("Unsupported URI $sourceUri", e)
    } ?: throw IOException("No content for $sourceUri")

    private companion object {
        const val AIFF_PREFIX_BYTES = 12
    }
}
