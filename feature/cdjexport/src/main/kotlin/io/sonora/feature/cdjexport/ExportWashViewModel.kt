package io.sonora.feature.cdjexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.feature.cdjexport.di.CdjExportIoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The covers behind the Export tab's look: the artwork the library shows for the tracks listed for export, found by the
 * tracks' document URIs. The first track's tints the tab; every row shows its own. Nothing is read from the picked
 * files themselves, so a file the library does not index simply has no cover here.
 */
@HiltViewModel
class ExportWashViewModel @Inject constructor(
    private val library: LibraryRepository,
    @CdjExportIoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val listed = MutableStateFlow<List<String>>(emptyList())

    /**
     * The artwork URI of each listed track the library has a cover for, by track URI. It follows the library, so a
     * cover the scanner finds later appears by itself; the lookup runs on the I/O dispatcher, not on a big library's
     * emission on the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val artwork: StateFlow<Map<String, String>> = listed
        .flatMapLatest { uris ->
            if (uris.isEmpty()) {
                flowOf(emptyMap())
            } else {
                library.tracks().map { ExportWash.artworkByUri(uris, it) }.flowOn(io)
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Sets the track [uris] to find covers for; the first one is the track that tints the tab. */
    fun show(uris: List<String>) {
        listed.value = uris
    }

    private companion object {
        /** Keeps the lookup through a rotation instead of restarting it. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
