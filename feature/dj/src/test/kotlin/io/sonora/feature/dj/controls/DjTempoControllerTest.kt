package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.TempoRange
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
class DjTempoControllerTest {

    /** Behaves like the DataStore repository: a preview wins over the stored tempo until a successful update. */
    private class FakeTempoStore(initial: PlaybackTempo = PlaybackTempo.DEFAULT) : PlaybackTempoRepository {
        val stored = MutableStateFlow(initial)
        private val previewed = MutableStateFlow<PlaybackTempo?>(null)
        val previews = mutableListOf<PlaybackTempo?>()
        var updates = 0
        var failNext = false

        override val tempo = combine(stored, previewed) { s, p -> p ?: s }

        override fun preview(tempo: PlaybackTempo?) {
            previews += tempo
            previewed.value = tempo
        }

        override suspend fun update(transform: (PlaybackTempo) -> PlaybackTempo) {
            updates++
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            stored.value = transform(stored.value)
            previewed.value = null
        }
    }

    private fun test(initial: PlaybackTempo = PlaybackTempo.DEFAULT, block: suspend TestScope.(FakeTempoStore, DjTempoController, MutableList<Throwable>) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeTempoStore(initial)
            val errors = mutableListOf<Throwable>()
            val controller = DjTempoController(store, backgroundScope, onError = { errors += it })
            block(store, controller, errors)
        }

    @Test
    fun `the controller shows the stored tempo`() = test(PlaybackTempo(speedBasisPoints = 10_250)) { _, controller, _ ->
        assertThat(controller.tempo.value).isEqualTo(PlaybackTempo(speedBasisPoints = 10_250))
    }

    @Test
    fun `dragging previews every new tempo and stores nothing`() = test { store, controller, _ ->
        controller.startDrag()
        controller.dragTo(0.5)
        controller.dragTo(1.0)

        assertThat(store.previews).containsExactly(PlaybackTempo(speedBasisPoints = 10_500), PlaybackTempo(speedBasisPoints = 11_000)).inOrder()
        assertThat(store.updates).isEqualTo(0)
        assertThat(controller.tempo.value.speedBasisPoints).isEqualTo(11_000)
        assertThat(store.stored.value).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `a move that does not change the tempo is not previewed again`() = test { store, controller, _ ->
        controller.startDrag()
        controller.dragTo(0.5)
        controller.dragTo(0.5001)
        controller.dragTo(0.5)

        assertThat(store.previews).hasSize(1)
    }

    @Test
    fun `the drag builds on the tempo it started from, keeping pitch, range and Master Tempo`() =
        test(PlaybackTempo(semitones = -2, masterTempo = false, range = TempoRange.SIXTEEN)) { store, controller, _ ->
            controller.startDrag()
            controller.dragTo(-0.5)

            assertThat(store.previews.single()).isEqualTo(PlaybackTempo(9_200, -2, masterTempo = false, range = TempoRange.SIXTEEN))
        }

    @Test
    fun `a release stores the tempo once and clears the preview`() = test { store, controller, _ ->
        controller.startDrag()
        controller.dragTo(0.1)
        controller.dragTo(0.3)
        controller.dragTo(0.5)

        controller.endDrag(0.5)

        assertThat(store.updates).isEqualTo(1)
        assertThat(store.stored.value.speedBasisPoints).isEqualTo(10_500)
        assertThat(controller.tempo.value).isEqualTo(store.stored.value)
    }

    @Test
    fun `a cancelled drag clears the preview and stores nothing`() = test { store, controller, _ ->
        controller.startDrag()
        controller.dragTo(0.8)

        controller.cancelDrag()

        assertThat(store.previews.last()).isNull()
        assertThat(store.updates).isEqualTo(0)
        assertThat(controller.tempo.value).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `a failed release drops the preview so the fader shows the stored tempo`() = test { store, controller, errors ->
        controller.startDrag()
        controller.dragTo(0.8)
        store.failNext = true

        controller.endDrag(0.8)

        assertThat(store.previews.last()).isNull()
        assertThat(controller.tempo.value).isEqualTo(PlaybackTempo.DEFAULT)
        assertThat(errors).hasSize(1)
    }

    @Test
    fun `reset goes back to 0 percent keeping the range, Master Tempo and pitch`() =
        test(PlaybackTempo(11_500, 3, masterTempo = false, range = TempoRange.SIXTEEN)) { store, controller, _ ->
            controller.reset()

            assertThat(store.stored.value).isEqualTo(PlaybackTempo(10_000, 3, masterTempo = false, range = TempoRange.SIXTEEN))
        }

    @Test
    fun `a narrower range clamps the speed to its end`() = test(PlaybackTempo(speedBasisPoints = 10_800)) { store, controller, _ ->
        controller.setRange(TempoRange.SIX)

        assertThat(store.stored.value.range).isEqualTo(TempoRange.SIX)
        assertThat(store.stored.value.speedBasisPoints).isEqualTo(10_600)
    }

    @Test
    fun `choosing the range already chosen stores nothing`() = test { store, controller, _ ->
        controller.setRange(TempoRange.TEN)

        assertThat(store.updates).isEqualTo(0)
    }

    @Test
    fun `Master Tempo toggles`() = test { store, controller, _ ->
        controller.toggleMasterTempo()
        assertThat(store.stored.value.masterTempo).isFalse()

        controller.toggleMasterTempo()
        assertThat(store.stored.value.masterTempo).isTrue()
    }

    @Test
    fun `a nudge moves the shown BPM by a tenth`() = test { store, controller, _ ->
        controller.nudge(trackBpm = 128.0, deltaBpm = 0.1)
        assertThat(store.stored.value.displayedBpm(128.0)).isEqualTo(128.1)

        controller.nudge(trackBpm = 128.0, deltaBpm = -0.1)
        controller.nudge(trackBpm = 128.0, deltaBpm = -0.1)
        assertThat(store.stored.value.displayedBpm(128.0)).isEqualTo(127.9)
    }

    @Test
    fun `a nudge on a 154 BPM track stores 0,06 percent and the readout shows it`() = test { store, controller, _ ->
        controller.nudge(trackBpm = 154.0, deltaBpm = DjTempoText.NUDGE_BPM)

        assertThat(store.stored.value.speedBasisPoints).isEqualTo(10_006)
        assertThat(controller.tempo.value.tempoPercent).isWithin(1e-9).of(0.06)
        assertThat(DjTempoText.tempoPercent(controller.tempo.value.tempoPercent, Locale("es", "ES"))).startsWith("+0,06")
        assertThat(DjTempoText.bpm(controller.tempo.value.displayedBpm(154.0), Locale("es", "ES"))).isEqualTo("154,1")
    }

    @Test
    fun `a nudge never rounds away at any playable track BPM`() {
        // 0.1 BPM is 10 000 * 0.1 / BPM basis points: at least 1 up to 2 000 BPM, so the speed always moves.
        var bpm = 40.0
        while (bpm <= 400.0) {
            val up = PlaybackTempo.DEFAULT.nudgeBpm(bpm, DjTempoText.NUDGE_BPM)
            val down = PlaybackTempo.DEFAULT.nudgeBpm(bpm, -DjTempoText.NUDGE_BPM)
            assertThat(up.speedBasisPoints).isGreaterThan(PlaybackTempo.NORMAL_SPEED_BASIS_POINTS)
            assertThat(down.speedBasisPoints).isLessThan(PlaybackTempo.NORMAL_SPEED_BASIS_POINTS)
            bpm += 0.37
        }
    }

    @Test
    fun `nudging needs the playing track's BPM`() = test { store, controller, _ ->
        assertThat(DjTempoController.canNudge(128.0)).isTrue()
        assertThat(DjTempoController.canNudge(null)).isFalse()
        assertThat(DjTempoController.canNudge(0.0)).isFalse()
        assertThat(DjTempoController.canNudge(Double.NaN)).isFalse()
        assertThat(DjTempoController.canNudge(Double.POSITIVE_INFINITY)).isFalse()

        controller.nudge(trackBpm = null, deltaBpm = 0.1)
        controller.nudge(trackBpm = Double.NaN, deltaBpm = 0.1)

        assertThat(store.updates).isEqualTo(0)
    }

    @Test
    fun `a tempo percent from accessibility is stored snapped and clamped to the range`() = test { store, controller, _ ->
        controller.setTempoPercent(2.5)
        assertThat(store.stored.value.speedBasisPoints).isEqualTo(10_250)

        controller.setTempoPercent(30.0)
        assertThat(store.stored.value.speedBasisPoints).isEqualTo(11_000)
    }

    @Test
    fun `a failed write is reported, not thrown`() = test { store, controller, errors ->
        store.failNext = true

        controller.reset()

        assertThat(errors.single()).isInstanceOf(IOException::class.java)
    }
}
