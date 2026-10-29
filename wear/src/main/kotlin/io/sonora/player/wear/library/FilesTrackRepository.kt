package io.sonora.player.wear.library

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The songs of a folder: each playable file ([TrackFiles.isPlayable]) is a song, described by the tags [readTags] finds in
 * it. A file with no readable tags is still a song, named after its file.
 *
 * @param readTags Reads one file's tags; `null` or an exception means it has none.
 */
class FilesTrackRepository(
    private val directory: () -> File,
    private val readTags: (File) -> RawTags?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TrackRepository {
    private val state = MutableStateFlow<List<WatchTrack>?>(null)

    override val tracks: StateFlow<List<WatchTrack>?> = state

    override suspend fun refresh() {
        state.value = withContext(dispatcher) { scan() }
    }

    private fun scan(): List<WatchTrack> {
        val files = directory().listFiles { file -> file.isFile && TrackFiles.isPlayable(file.name) }.orEmpty()
        return TrackOrder.sorted(files.map(::trackOf))
    }

    private fun trackOf(file: File): WatchTrack {
        val tags = runCatching { readTags(file) }.getOrNull() ?: RawTags(null, null, null, null, null, null)
        return tags.toWatchTrack(id = file.name, uri = file.toURI().toString(), fileName = file.name)
    }
}
