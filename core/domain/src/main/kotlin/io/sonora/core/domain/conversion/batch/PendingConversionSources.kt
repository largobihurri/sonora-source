package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.model.PickedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A source waiting for the next batch: a whole folder (one row), a single file or a library song. */
data class PendingSource(
    val pick: PickedSource,
    val displayName: String,
    /** A second line for the row, e.g. a song's artist; `null` to describe it by its kind. */
    val detail: String? = null,
    /** The codec the library read for a library song (`ALAC`, `AAC`...); `null` for anything else or when unknown. */
    val codec: String? = null,
) {
    val isFolder: Boolean get() = pick is PickedSource.Folder
}

/**
 * The sources picked for the next conversion batch, one list for the whole process: the Converter's pickers and the
 * Library's "Convert…" add here, and the Converter lists, removes and starts them. Hold it as a singleton.
 */
class PendingConversionSources {
    private val _sources = MutableStateFlow<List<PendingSource>>(emptyList())
    val sources: StateFlow<List<PendingSource>> = _sources.asStateFlow()

    /** Appends [picked] in order; a source already listed (same URI) stays as it was. */
    fun add(picked: List<PendingSource>) =
        _sources.update { listed -> (listed + picked).distinctBy { it.pick.uri } }

    fun remove(uri: String) = _sources.update { listed -> listed.filterNot { it.pick.uri == uri } }

    /** Drops the sources a started batch took ([uris]); sources added meanwhile stay for the next one. */
    fun removeAll(uris: Set<String>) = _sources.update { listed -> listed.filterNot { it.pick.uri in uris } }
}
