package io.sonora.core.playback.crossfade

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The crossfade handover on a fake clock: a fake main player whose position runs with the clock while it plays, and a
 * fake deck that records what the coordinator asks of the players.
 */
class CrossfadeCoordinatorTest {

    private var now = 0L

    // The main player, as the coordinator reads it.
    private var entry = "A"
    private var position = 0L
    private var duration = SONG
    private var playing = true
    private var fade = 6_000L

    private val deck = FakeDeck()
    private val coordinator = CrossfadeCoordinator(deck) { now }

    private fun state() = MainState(entry, position, duration, playing, fade)

    private inner class FakeDeck : CrossfadeDeck {
        var preparedAt: Long? = null
        var tailPlaying = false
        var tailStartedFrom: Long? = null
        override var tailReady = true
        var tailLevel = 0f
        var mainLevel = 1f
        var advances = 0
        val log = mutableListOf<String>()

        override fun prepareTail(positionMs: Long) {
            preparedAt = positionMs
            log += "prepare $positionMs at $now"
        }

        override fun playTail(mainPositionMs: Long) {
            tailPlaying = true
            tailStartedFrom = mainPositionMs
            log += "play at $now"
        }

        override fun stopTail() {
            preparedAt = null
            tailPlaying = false
            log += "stop at $now"
        }

        override fun setTailGain(gain: Float) {
            tailLevel = gain
        }

        override fun setMainGain(gain: Float) {
            mainLevel = gain
        }

        /** Whether the move on is reported as a change of entry (not when the next entry is the same song). */
        var advanceReported = true

        /** As the real main player does, the move is reported at once, as a seek. */
        override fun advanceMain(): Boolean {
            advances++
            log += "advance at $now"
            position = 0L
            duration = SONG
            if (!advanceReported) return false
            entry = "B"
            coordinator.onEntryChanged(manual = true)
            return true
        }
    }

    /** Lets [ms] go by, updating the coordinator whenever it asked to be, and once more at the end (an event). */
    private fun pass(ms: Long) {
        val end = now + ms
        var next = coordinator.update(state())?.let { now + it }
        while (next != null && maxOf(next, now + 1) <= end) {
            step(maxOf(next, now + 1) - now)
            next = coordinator.update(state())?.let { now + it }
        }
        step(end - now)
        coordinator.update(state())
    }

    private fun step(ms: Long) {
        now += ms
        if (playing) position += ms
    }

    /** Plays song A from [from] up to the middle of its 6 s fade into B. */
    private fun playToFadeMiddle(from: Long = 180_000L) {
        position = from
        pass(FADE_START - from + SWAP + 3_000)
    }

    @Test
    fun `the tail is loaded a few seconds ahead, silent and paused, where it will start`() {
        position = 180_000L

        pass(ARM_AT - 180_000L - 1)
        assertThat(deck.preparedAt).isNull()
        pass(1)

        assertThat(deck.preparedAt).isEqualTo(TAIL_START)
        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.tailLevel).isEqualTo(0f)
    }

    @Test
    fun `the handover runs on time - silent tail, swap, then the equal-power fade`() {
        position = 180_000L
        pass(TAIL_START - 180_000L - 1)
        assertThat(deck.tailPlaying).isFalse()

        pass(1)
        assertThat(deck.tailPlaying).isTrue()
        assertThat(deck.tailStartedFrom).isEqualTo(TAIL_START)
        assertThat(deck.tailLevel).isEqualTo(0f)
        assertThat(deck.mainLevel).isEqualTo(1f)

        // The swap: the same music moves from the main player to the tail.
        pass(LEAD_IN + SWAP / 2)
        assertThat(deck.tailLevel).isWithin(0.01f).of(0.707f)
        assertThat(deck.mainLevel).isWithin(0.01f).of(0.707f)
        assertThat(entry).isEqualTo("A")

        pass(SWAP / 2)
        assertThat(deck.advances).isEqualTo(1)
        assertThat(entry).isEqualTo("B")
        assertThat(deck.tailLevel).isEqualTo(1f)
        assertThat(deck.mainLevel).isEqualTo(0f)

        // Its own move on is no skip: B comes in over the whole fade, not quickly.
        pass(300)
        assertThat(deck.mainLevel).isWithin(0.01f).of(0.078f)

        pass(2_700)
        assertThat(deck.tailLevel).isWithin(0.01f).of(0.707f)
        assertThat(deck.mainLevel).isWithin(0.01f).of(0.707f)

        pass(2_999)
        assertThat(deck.tailPlaying).isTrue()
        pass(1)
        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
        assertThat(deck.log.map { it.substringBefore(' ') }).containsExactly("prepare", "play", "advance", "stop").inOrder()
    }

    @Test
    fun `a skip during the fade fades the tail out and the new song in within 300 ms`() {
        playToFadeMiddle()
        val tailBefore = deck.tailLevel
        assertThat(tailBefore).isWithin(0.01f).of(0.707f)

        entry = "C"
        position = 0L
        coordinator.onEntryChanged(manual = true)
        assertThat(deck.mainLevel).isEqualTo(0f)

        pass(150)
        assertThat(deck.tailLevel).isWithin(0.01f).of(tailBefore * 0.707f)
        assertThat(deck.mainLevel).isWithin(0.01f).of(0.707f)

        pass(150)
        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
        assertThat(deck.advances).isEqualTo(1)
    }

    @Test
    fun `a move on that is reported as no change of entry does not swallow the next skip`() {
        deck.advanceReported = false // the next entry is the same song (a repeated queue entry)
        playToFadeMiddle()
        assertThat(deck.advances).isEqualTo(1)

        entry = "C"
        position = 0L
        coordinator.onEntryChanged(manual = true)
        pass(QUICK_FADE)

        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a skip while the tail waits silently drops it and plays the new song`() {
        coordinator.quickFades = true
        position = TAIL_START + 100

        pass(0)
        position = TAIL_START - 1_000
        pass(1_000)
        assertThat(deck.tailPlaying).isTrue()

        entry = "C"
        position = 0L
        coordinator.onEntryChanged(manual = true)

        assertThat(deck.tailPlaying).isFalse()
        pass(300)
        assertThat(deck.mainLevel).isEqualTo(1f)
        assertThat(deck.advances).isEqualTo(0)
    }

    @Test
    fun `a pause during the fade stops the tail and leaves the new song at full level`() {
        playToFadeMiddle()

        playing = false
        pass(10)

        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
        playing = true
        pass(5_000)
        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a tail not ready in time leaves the song to end gapless`() {
        deck.tailReady = false
        position = 180_000L

        pass(TAIL_START - 180_000L)

        assertThat(deck.preparedAt).isNull()
        pass(SONG - TAIL_START - 1)
        assertThat(deck.advances).isEqualTo(0)
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a seek back before the tail starts lets go of it, and it is loaded again later`() {
        position = ARM_AT
        pass(0)
        assertThat(deck.preparedAt).isEqualTo(TAIL_START)

        position = 60_000L
        coordinator.onSeek()
        pass(1)
        assertThat(deck.preparedAt).isNull()

        position = ARM_AT - 1_000
        pass(1_000)
        assertThat(deck.preparedAt).isEqualTo(TAIL_START)
    }

    @Test
    fun `a seek while the tail runs silently cancels the crossfade`() {
        position = TAIL_START - 1_000
        pass(1_100)
        assertThat(deck.tailPlaying).isTrue()

        position = FADE_START + 500
        coordinator.onSeek()
        pass(SONG - FADE_START - 501)

        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.advances).isEqualTo(0)
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a song resumed too close to its end plays out gapless`() {
        position = TAIL_START - 200

        pass(SONG - TAIL_START)

        assertThat(deck.preparedAt).isNull()
        assertThat(deck.advances).isEqualTo(0)
    }

    @Test
    fun `a new plan for the song, such as a queue edit, replaces the loaded tail`() {
        position = ARM_AT
        pass(0)
        assertThat(deck.preparedAt).isEqualTo(TAIL_START)

        fade = 0L
        pass(1)
        assertThat(deck.preparedAt).isNull()

        pass(SONG - ARM_AT - 2)
        assertThat(deck.advances).isEqualTo(0)
    }

    @Test
    fun `nothing is prepared while paused or with crossfade off`() {
        playing = false
        position = ARM_AT
        pass(1_000)
        assertThat(deck.preparedAt).isNull()

        playing = true
        fade = 0L
        pass(SONG - ARM_AT - 1_001)
        assertThat(deck.log).isEmpty()
    }

    @Test
    fun `a manual skip fades the new song in quickly, only while crossfade is on`() {
        coordinator.quickFades = true
        entry = "B"
        coordinator.onEntryChanged(manual = true)
        assertThat(deck.mainLevel).isEqualTo(0f)
        pass(150)
        assertThat(deck.mainLevel).isWithin(0.01f).of(0.707f)
        pass(150)
        assertThat(deck.mainLevel).isEqualTo(1f)

        coordinator.quickFades = false
        entry = "C"
        coordinator.onEntryChanged(manual = true)
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a song that follows on its own, gapless, is not faded in`() {
        coordinator.quickFades = true
        entry = "B"

        coordinator.onEntryChanged(manual = false)

        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `a tail that fails during the fade is dropped and the new song keeps fading in`() {
        playToFadeMiddle()

        coordinator.onTailEnded()
        assertThat(deck.tailPlaying).isFalse()
        pass(1_500)

        assertThat(deck.mainLevel).isWithin(0.01f).of(0.924f)
        pass(1_500)
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    @Test
    fun `release lets go of the tail and restores the main level`() {
        playToFadeMiddle()

        coordinator.release()

        assertThat(deck.tailPlaying).isFalse()
        assertThat(deck.mainLevel).isEqualTo(1f)
    }

    private companion object {
        const val SONG = 200_000L
        const val FADE_START = SONG - 6_000L
        const val LEAD_IN = CrossfadeCoordinator.LEAD_IN_MS
        const val SWAP = CrossfadeCoordinator.SWAP_MS
        const val QUICK_FADE = CrossfadeCoordinator.QUICK_FADE_MS
        const val TAIL_START = FADE_START - LEAD_IN
        const val ARM_AT = TAIL_START - CrossfadeCoordinator.PREPARE_LEAD_MS
    }
}
