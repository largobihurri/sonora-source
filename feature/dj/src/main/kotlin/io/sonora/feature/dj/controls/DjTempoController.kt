package io.sonora.feature.dj.controls

import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.TempoRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The DJ tempo section's logic over the one app-wide [PlaybackTempoRepository]: the current [tempo] (a fader preview
 * included), and every control's action. A fader drag is heard as it moves but written once: [startDrag], then
 * [dragTo] previews each new tempo ([PlaybackTempoRepository.preview]), and [endDrag] stores the last one with a single
 * [PlaybackTempoRepository.update]; [cancelDrag], or a failed or cancelled write, drops the preview so the player goes
 * back to the stored tempo. Other actions store at once. Failures go to [onError]; nothing throws.
 *
 * Writes run in [scope] (the view model's).
 */
class DjTempoController(
    private val store: PlaybackTempoRepository,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) {
    /** The tempo now heard: the stored one, or the fader's preview while it is dragged. */
    val tempo: StateFlow<PlaybackTempo> = store.tempo.stateIn(scope, SharingStarted.Eagerly, PlaybackTempo.DEFAULT)

    /** The tempo the drag in progress started from; its pitch, range and Master Tempo stay as they were. */
    private var dragBase: PlaybackTempo? = null
    private var lastPreview: PlaybackTempo? = null

    /** A fader drag starts from the tempo shown now. */
    fun startDrag() {
        dragBase = tempo.value
        lastPreview = null
    }

    /** The fader is at [fraction] (-1..1): previews that tempo, unless it is the one already previewed. */
    fun dragTo(fraction: Double) {
        val base = dragBase ?: tempo.value.also { dragBase = it }
        val next = TempoFaderMath.tempoAt(base, fraction)
        if (next == lastPreview) return
        lastPreview = next
        store.preview(next)
    }

    /** The fader was released at [fraction]: stores that tempo once; the repository then clears the preview. */
    fun endDrag(fraction: Double) {
        finishDrag()
        write(dropPreviewOnFailure = true) { TempoFaderMath.tempoAt(it, fraction) }
    }

    /** The drag was taken away: drops the preview, stores nothing. */
    fun cancelDrag() {
        finishDrag()
        store.preview(null)
    }

    /** Back to 0 %, keeping the range, Master Tempo and pitch. */
    fun reset() = write { it.resetTempo() }

    /** A new fader reach; a speed outside it moves to its nearest end. The range already chosen stores nothing. */
    fun setRange(range: TempoRange) {
        if (tempo.value.range != range) write { it.withRange(range) }
    }

    fun toggleMasterTempo() = write { it.withMasterTempo(!it.masterTempo) }

    /**
     * Moves the BPM shown for a track of [trackBpm] (at its own speed) by [deltaBpm], landing on the displayed tenths
     * ([PlaybackTempo.nudgeBpm]); nothing without a usable BPM ([canNudge]).
     */
    fun nudge(trackBpm: Double?, deltaBpm: Double) {
        if (trackBpm == null || !canNudge(trackBpm)) return
        write { it.nudgeBpm(trackBpm, deltaBpm) }
    }

    /** A tempo [percent] off normal, e.g. from an accessibility service; snapped and clamped to the range. */
    fun setTempoPercent(percent: Double) = write { it.withTempoPercent(percent) }

    private fun finishDrag() {
        dragBase = null
        lastPreview = null
    }

    private fun write(dropPreviewOnFailure: Boolean = false, transform: (PlaybackTempo) -> PlaybackTempo) {
        scope.launch {
            try {
                store.update(transform)
            } catch (e: CancellationException) {
                // A preview left behind would keep playing a tempo that was never stored.
                if (dropPreviewOnFailure) store.preview(null)
                throw e
            } catch (e: Exception) {
                if (dropPreviewOnFailure) store.preview(null)
                onError(e)
            }
        }
    }

    companion object {
        /** Whether the ±0.1 BPM buttons can work: they need the playing track's BPM, finite and above 0. */
        fun canNudge(trackBpm: Double?): Boolean = trackBpm != null && trackBpm > 0.0 && trackBpm.isFinite()
    }
}
