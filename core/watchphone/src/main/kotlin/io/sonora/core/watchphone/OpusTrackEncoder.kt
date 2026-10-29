package io.sonora.core.watchphone

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.watchsync.TrackEncodingException
import io.sonora.core.watchsync.sender.SendCandidate
import io.sonora.core.watchsync.sender.TrackEncoder
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TrackEncoder] on the phone's converter with the `WATCH_OPUS` profile (W1). A song is encoded into a `.part` file in the
 * cache and renamed when whole, so a finished file is always complete; it stays until the watch confirms the song
 * ([release]), which is what lets an interrupted send resume without encoding again. The name carries the original's
 * size, so a song changed in between is encoded afresh. Staged files untouched for a day are discarded.
 *
 * Does not run [AudioConverter.verify] (a second full decode per song): the sender's checksum already proves the watch got
 * the bytes encoded here, and the converter's own length check covers a short encode.
 */
@Singleton
class OpusTrackEncoder @Inject constructor(
    @ApplicationContext context: Context,
    private val converter: AudioConverter,
) : TrackEncoder {
    private val directory = File(context.cacheDir, STAGING_DIRECTORY)
    private var purged = false

    override suspend fun encode(candidate: SendCandidate, onProgress: (Float) -> Unit): File {
        directory.mkdirs()
        purgeStaleOnce()
        val finished = finishedFile(candidate)
        if (finished.isFile && finished.length() > 0) {
            onProgress(1f)
            return finished
        }
        val partial = File(directory, finished.name + PARTIAL_SUFFIX)
        partial.delete()
        var done = false
        try {
            val result = try {
                converter.convert(
                    ConversionRequest(
                        sourceUri = candidate.sourceUri,
                        outputUri = partial.toUri().toString(),
                        profile = SendSelection.PROFILE,
                    ),
                    onProgress,
                )
            } catch (e: PcmDecodeException) {
                throw TrackEncodingException("Cannot decode ${candidate.title}", e)
            }
            if (result.outputFrames <= 0) throw TrackEncodingException("Nothing was encoded for ${candidate.title}")
            if (!partial.renameTo(finished)) throw IOException("Cannot move the encoded file into place")
            done = true
            return finished
        } finally {
            if (!done) partial.delete()
        }
    }

    override fun release(candidate: SendCandidate) {
        finishedFile(candidate).delete()
    }

    private fun finishedFile(candidate: SendCandidate) = File(directory, "${candidate.id}-${candidate.sourceVersion}.opus")

    private fun purgeStaleOnce() {
        if (purged) return
        purged = true
        val limit = System.currentTimeMillis() - STALE_MS
        directory.listFiles().orEmpty().filter { it.lastModified() < limit }.forEach {
            if (it.delete()) Log.i(TAG, "discarded stale ${it.name}")
        }
    }

    private companion object {
        const val TAG = "SonoraWatchSync"
        const val STAGING_DIRECTORY = "watch-staging"
        const val PARTIAL_SUFFIX = ".part"
        const val STALE_MS = 24L * 3600 * 1000
    }
}
