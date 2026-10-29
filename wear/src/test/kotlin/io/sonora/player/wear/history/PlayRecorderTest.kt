package io.sonora.player.wear.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayRecorderTest {
    private var wall = 1_000_000L
    private var mono = 50L
    private val saved = mutableListOf<PlayLogEntry>()
    private val recorder = PlayRecorder(wallClock = { wall }, monotonic = { mono }, sink = { saved += it })

    private fun advance(ms: Long) {
        wall += ms
        mono += ms
    }

    private val last get() = saved.last()

    @Test
    fun `a play starts when sound starts and is extended on pause`() {
        recorder.trackChanged("t1")
        advance(5_000) // loading: not a play yet
        recorder.playingChanged(true)
        val startedAt = wall
        advance(30_000)
        recorder.playingChanged(false)

        assertThat(saved).containsExactly(PlayLogEntry("t1", startedAt, 30_000))
    }

    @Test
    fun `pause and resume extend the same play with the time actually heard`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        val startedAt = wall
        advance(10_000)
        recorder.playingChanged(false)
        advance(600_000) // paused: not counted
        recorder.playingChanged(true)
        advance(5_000)
        recorder.playingChanged(false)

        assertThat(saved.map { it.startedAtMs }.distinct()).containsExactly(startedAt)
        assertThat(last.listenedMs).isEqualTo(15_000)
    }

    @Test
    fun `a track change while playing closes the play and starts a new one`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        advance(20_000)
        recorder.trackChanged("t2")
        val secondStart = wall
        advance(7_000)
        recorder.playingChanged(false)

        assertThat(saved.first { it.trackId == "t1" }.listenedMs).isEqualTo(20_000)
        assertThat(last).isEqualTo(PlayLogEntry("t2", secondStart, 7_000))
    }

    @Test
    fun `a track change while paused starts nothing until sound starts`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        advance(1_000)
        recorder.playingChanged(false)
        saved.clear()

        recorder.trackChanged("t2")
        advance(9_000)

        assertThat(saved).isEmpty()
    }

    @Test
    fun `a finished song restarted is a fresh play`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        val first = wall
        advance(60_000)
        recorder.ended()
        advance(1_000)
        recorder.playingChanged(true)
        val second = wall
        advance(3_000)
        recorder.playingChanged(false)

        assertThat(saved.map { it.startedAtMs }.distinct()).containsExactly(first, second).inOrder()
        assertThat(last.listenedMs).isEqualTo(3_000)
    }

    @Test
    fun `a tick extends the running play and an idle tick writes nothing`() {
        recorder.tick()
        assertThat(saved).isEmpty()

        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        advance(30_000)
        recorder.tick()
        advance(30_000)
        recorder.tick()

        assertThat(saved.map { it.listenedMs }).containsExactly(30_000L, 60_000L).inOrder()
    }

    @Test
    fun `a play that made no sound is not recorded`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        recorder.playingChanged(false)
        recorder.trackChanged("t2")

        assertThat(saved).isEmpty()
    }

    @Test
    fun `clearing the queue closes the play`() {
        recorder.trackChanged("t1")
        recorder.playingChanged(true)
        advance(4_000)
        recorder.trackChanged(null)
        advance(4_000)
        recorder.playingChanged(false)

        assertThat(saved).hasSize(1)
        assertThat(last.listenedMs).isEqualTo(4_000)
    }
}
