package io.sonora.core.data.archive.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveNaming
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.model.Track
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The app-cache folder of the compression jobs, one subfolder per job: its request (`job.json`, the songs' URIs and
 * tags, never a password) and the archive it writes, kept to be shared or saved. Folders untouched for [maxAgeMs]
 * are removed by [cleanUp], which runs whenever a compression is scheduled or starts; the system may also clear the
 * cache on its own when storage runs low.
 */
class ArchiveCache internal constructor(
    private val root: File,
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(1),
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, FOLDER))

    fun jobFolder(jobId: String): File = File(root, jobId)

    /** Where job [jobId] writes the archive of the album or playlist called [name]. */
    fun archiveFile(jobId: String, name: String): File = File(jobFolder(jobId), ArchiveNaming.archiveFileName(name))

    /** Writes the request of compression job [jobId], for its worker to read even after the app was restarted. */
    fun writeRequest(jobId: String, request: CompressionRequest) {
        val folder = jobFolder(jobId)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        File(folder, REQUEST_FILE).writeText(json.encodeToString(RequestDto.serializer(), RequestDto.of(request)))
    }

    /** The request of job [jobId]; `null` when it is gone (cache cleared) or unreadable. */
    fun readRequest(jobId: String): CompressionRequest? = try {
        json.decodeFromString(RequestDto.serializer(), File(jobFolder(jobId), REQUEST_FILE).readText()).toRequest()
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Removes the job folders (and stray files) not touched for [maxAgeMs] at [nowMs], except those of [keep]. */
    fun cleanUp(nowMs: Long, keep: Set<String> = emptySet()) {
        for (entry in root.listFiles().orEmpty()) {
            if (entry.name in keep) continue
            if (isExpired(lastActivity(entry), nowMs, maxAgeMs)) entry.deleteRecursively()
        }
    }

    /** The newest modification time of [entry] and anything in it. */
    private fun lastActivity(entry: File): Long = entry.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0

    companion object {
        const val FOLDER: String = "archives"
        private const val REQUEST_FILE = "job.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Whether something last touched at [lastActivityMs] is older than [maxAgeMs] at [nowMs]. */
        fun isExpired(lastActivityMs: Long, nowMs: Long, maxAgeMs: Long): Boolean = nowMs - lastActivityMs > maxAgeMs
    }

    @Serializable
    private data class RequestDto(val kind: String, val name: String, val tracks: List<TrackDto>) {
        fun toRequest() = CompressionRequest(ArchiveKind.valueOf(kind), name, tracks.map(TrackDto::toTrack))

        companion object {
            fun of(request: CompressionRequest) =
                RequestDto(request.kind.name, request.name, request.tracks.map(TrackDto::of))
        }
    }

    /** What packing needs of a track: where it is, how big, and the tags the manifest keeps. */
    @Serializable
    private data class TrackDto(
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String? = null,
        val durationMs: Long = 0,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val mimeType: String,
        val sizeBytes: Long,
    ) {
        fun toTrack() = Track(
            id = 0, uri = uri, title = title, artist = artist, album = album, albumArtist = albumArtist,
            durationMs = durationMs, trackNumber = trackNumber, discNumber = discNumber, year = null, albumId = 0,
            artworkUri = null, mimeType = mimeType, sizeBytes = sizeBytes,
        )

        companion object {
            fun of(track: Track) = TrackDto(
                track.uri, track.title, track.artist, track.album, track.albumArtist, track.durationMs,
                track.trackNumber, track.discNumber, track.mimeType, track.sizeBytes,
            )
        }
    }
}
