package io.sonora.core.data.archive

import io.sonora.core.data.library.saf.ListedAudioFile
import io.sonora.core.data.library.tags.TagReader
import io.sonora.core.domain.archive.ExtractedFile
import io.sonora.core.domain.archive.ExtractedSongTags
import io.sonora.core.domain.archive.SongCredits
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ExtractedSongTags] through the library's [TagReader], which picks the reader from the file's name. */
class TagReaderExtractedSongTags @Inject constructor(private val reader: TagReader) : ExtractedSongTags {

    override suspend fun read(file: ExtractedFile): SongCredits? = withContext(Dispatchers.IO) {
        val listed = ListedAudioFile(
            documentUri = file.uri,
            fileName = file.path,
            mimeType = "",
            sizeBytes = 0,
            lastModifiedMs = 0,
            folderPath = "",
            folderImageUri = null,
        )
        reader.read(listed)?.tags?.let { SongCredits(it.title, it.artist) }
    }
}
