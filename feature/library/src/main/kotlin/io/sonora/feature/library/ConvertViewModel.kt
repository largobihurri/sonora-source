package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.PendingSource
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What became of a "Convert…" request. */
sealed interface ConvertEvent {
    /** [count] songs wait in the Converter now; the screen opens it. */
    data class Sent(val count: Int) : ConvertEvent

    data object NothingToConvert : ConvertEvent

    data object Failed : ConvertEvent
}

/** A library song as a converter source: picked by its document URI, named by its title and artist. */
internal fun Track.asConversionSource() = PendingSource(PickedSource.Track(uri), title, detail = artist, codec = codec)

/**
 * "Convert…" in the Library (song menu, album, artist, folder and playlist pages): sends the songs to the Converter's
 * [pending] sources, shared with the Converter tab, and asks the screen to open it ([events]).
 */
@HiltViewModel
class ConvertViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val folders: FolderBrowser,
    private val pending: PendingConversionSources,
) : ViewModel() {

    private val _events = Channel<ConvertEvent>(Channel.BUFFERED)
    val events: Flow<ConvertEvent> = _events.receiveAsFlow()

    fun convert(source: PlaylistSource) {
        viewModelScope.launch {
            val event = try {
                val tracks = source.tracks(library, folders)
                if (tracks.isEmpty()) {
                    ConvertEvent.NothingToConvert
                } else {
                    pending.add(tracks.map { it.asConversionSource() })
                    ConvertEvent.Sent(tracks.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot send songs to the converter", e)
                ConvertEvent.Failed
            }
            _events.send(event)
        }
    }

    private companion object {
        const val TAG = "SonoraConvert"
    }
}
