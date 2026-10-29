package io.sonora.feature.dj

import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackCues
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.DjLoopState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** The cues of each uri; a save or delete changes what the screens see unless [reachesScreens] is `false` (a slow store). */
internal class FakeCues : DjTrackCues {
    private val flows = HashMap<String, MutableStateFlow<List<DjCue>>>()
    val active = HashSet<String>()
    val saves = ArrayList<Pair<String, DjCue>>()
    val deletes = ArrayList<Pair<String, String>>()
    var identified = true
    var failure: Exception? = null
    var reachesScreens = true

    fun flowOf(uri: String) = flows.getOrPut(uri) { MutableStateFlow(emptyList()) }

    fun set(uri: String, cues: List<DjCue>) {
        flowOf(uri).value = cues
    }

    override fun cuesOf(uri: String): Flow<List<DjCue>> =
        flowOf(uri).onStart { active += uri }.onCompletion { active -= uri }

    override suspend fun save(uri: String, cue: DjCue): Boolean {
        failure?.let { throw it }
        if (!identified) return false
        saves += uri to cue
        if (reachesScreens) flowOf(uri).value = DjCues.sorted(flowOf(uri).value.filterNot { it.id == cue.id } + cue)
        return true
    }

    override suspend fun delete(uri: String, cueId: String): Boolean {
        if (!identified) return false
        deletes += uri to cueId
        if (reachesScreens) flowOf(uri).value = flowOf(uri).value.filterNot { it.id == cueId }
        return true
    }
}

internal class FakeQuantize(on: Boolean = true) : DjQuantizeSettings {
    private val state = MutableStateFlow(on)
    override val quantize: StateFlow<Boolean> = state

    override fun setQuantize(on: Boolean) {
        state.value = on
    }
}

/** Records what the screen asks of the loop controller; the state is set by the test. */
internal class FakeLoops : DjLoopController {
    override val loop = MutableStateFlow(DjLoopState(available = true))
    val calls = ArrayList<String>()

    override fun loopIn() { calls += "in" }
    override fun loopOut() { calls += "out" }
    override fun beatLoop(beats: Double) { calls += "beat $beats" }
    override fun halve() { calls += "half" }
    override fun double() { calls += "double" }
    override fun exit() { calls += "exit" }
    override fun reloop() { calls += "reloop" }
    override fun clear() { calls += "clear" }
    override fun engage(gridInMs: Double, gridOutMs: Double) { calls += "engage $gridInMs $gridOutMs" }
}
