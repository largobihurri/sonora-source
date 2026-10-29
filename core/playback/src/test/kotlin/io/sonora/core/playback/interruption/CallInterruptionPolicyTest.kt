package io.sonora.core.playback.interruption

import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.interruption.CallInterruptionPolicy.Action
import org.junit.Test

class CallInterruptionPolicyTest {

    private var now = 0L
    private val policy = CallInterruptionPolicy(settleMs = SETTLE, maxInterruptionMs = MAX)

    private fun after(ms: Long) {
        now += ms
    }

    private fun call(active: Boolean): Action = policy.onCallProfile(active, now)

    private fun tick(otherMediaActive: Boolean = false): Action = policy.tick(now, otherMediaActive)

    /** Ticks every [step] ms for [ms] ms, as the service does while it holds a pause; returns every action. */
    private fun runFor(ms: Long, step: Long = 250, otherMediaActive: Boolean = false): List<Action> {
        val actions = mutableListOf<Action>()
        var left = ms
        while (left > 0) {
            val stepMs = minOf(step, left)
            after(stepMs)
            left -= stepMs
            actions += tick(otherMediaActive)
        }
        return actions.filter { it != Action.NONE }
    }

    private fun playing() {
        policy.onPlayer(audible = true, byUser = true, now)
    }

    @Test
    fun `entering a call while playing pauses, and leaving it resumes after the settle delay`() {
        playing()

        assertThat(call(true)).isEqualTo(Action.PAUSE)
        assertThat(policy.isHolding).isTrue()
        after(5_000)
        assertThat(tick()).isEqualTo(Action.NONE)

        assertThat(call(false)).isEqualTo(Action.NONE)
        assertThat(runFor(SETTLE - 1, step = SETTLE - 1)).isEmpty()
        assertThat(runFor(1)).containsExactly(Action.RESUME)
        assertThat(policy.isHolding).isFalse()
        assertThat(runFor(10_000)).isEmpty()
    }

    @Test
    fun `nothing happens when the player was already paused`() {
        policy.onPlayer(audible = false, byUser = true, now)

        assertThat(call(true)).isEqualTo(Action.NONE)
        assertThat(call(false)).isEqualTo(Action.NONE)
        assertThat(runFor(5_000)).isEmpty()
        assertThat(policy.isHolding).isFalse()
    }

    @Test
    fun `a pause by the user during the call is never undone`() {
        playing()
        call(true)
        // The user plays on through the call, then pauses.
        policy.onPlayer(audible = true, byUser = true, now)
        policy.onPlayer(audible = false, byUser = true, now)

        call(false)
        assertThat(runFor(5_000)).isEmpty()
    }

    @Test
    fun `playing again by hand during the call keeps playing and is not paused again in that call`() {
        playing()
        call(true)

        assertThat(policy.onPlayer(audible = true, byUser = true, now)).isEqualTo(Action.NONE)
        assertThat(policy.isHolding).isFalse()
        // The call profile is reported again (a route change): the user's choice stands.
        assertThat(call(true)).isEqualTo(Action.NONE)
        call(false)
        assertThat(runFor(5_000)).isEmpty()

        // The next call pauses again.
        assertThat(call(true)).isEqualTo(Action.PAUSE)
    }

    @Test
    fun `playing again by hand after the call, before the settle delay, does not resume twice`() {
        playing()
        call(true)
        call(false)
        after(100)
        policy.onPlayer(audible = true, byUser = true, now)

        assertThat(runFor(5_000)).isEmpty()
    }

    @Test
    fun `a track change during the call cancels the resume`() {
        playing()
        call(true)
        policy.cancel()

        call(false)
        assertThat(runFor(5_000)).isEmpty()
        assertThat(policy.isHolding).isFalse()
    }

    @Test
    fun `a long interruption stays paused`() {
        playing()
        call(true)
        after(MAX)
        call(false)

        assertThat(runFor(5_000)).isEmpty()
        assertThat(policy.isHolding).isFalse()
    }

    @Test
    fun `an interruption just under the cap resumes`() {
        playing()
        call(true)
        after(MAX - 1)
        call(false)

        assertThat(runFor(SETTLE)).containsExactly(Action.RESUME)
    }

    @Test
    fun `the hold expires once the cap passes even while the call goes on`() {
        playing()
        call(true)

        assertThat(runFor(MAX)).isEmpty()
        assertThat(policy.isHolding).isFalse()
        call(false)
        assertThat(runFor(5_000)).isEmpty()
    }

    @Test
    fun `a flapping call mode pauses once and resumes once`() {
        playing()

        assertThat(call(true)).isEqualTo(Action.PAUSE)
        val actions = mutableListOf<Action>()
        repeat(6) {
            actions += call(false)
            actions += runFor(SETTLE / 2)
            actions += call(true)
            actions += runFor(300)
        }
        assertThat(actions.filter { it != Action.NONE }).isEmpty()

        call(false)
        assertThat(runFor(5_000)).containsExactly(Action.RESUME)
    }

    @Test
    fun `the same call state reported twice changes nothing`() {
        playing()
        call(true)
        assertThat(call(true)).isEqualTo(Action.NONE)
        call(false)
        after(SETTLE / 2)
        // A repeated "no call" must not restart the settle delay.
        call(false)

        assertThat(runFor(SETTLE / 2)).containsExactly(Action.RESUME)
    }

    @Test
    fun `another app's media playing when the resume is due keeps Sonora paused`() {
        playing()
        call(true)
        call(false)

        assertThat(runFor(5_000, otherMediaActive = true)).isEmpty()
        assertThat(policy.isHolding).isFalse()
    }

    @Test
    fun `a call that takes audio focus is left to the focus handling`() {
        playing()
        // A phone call: focus goes first, the player is held silent by Media3.
        assertThat(policy.onPlayer(audible = false, byUser = false, now)).isEqualTo(Action.NONE)
        assertThat(call(true)).isEqualTo(Action.NONE)
        call(false)
        // Focus comes back after the call: Media3 resumes; Sonora does nothing more.
        assertThat(policy.onPlayer(audible = true, byUser = false, now)).isEqualTo(Action.NONE)
        assertThat(runFor(5_000)).isEmpty()
    }

    @Test
    fun `focus coming back while the call profile is still on pauses until the call ends`() {
        playing()
        policy.onPlayer(audible = false, byUser = false, now)
        call(true)

        assertThat(policy.onPlayer(audible = true, byUser = false, now)).isEqualTo(Action.PAUSE)
        call(false)
        assertThat(runFor(5_000)).containsExactly(Action.RESUME)
    }

    @Test
    fun `a stop during the call cancels the resume`() {
        playing()
        call(true)
        policy.onPlayer(audible = false, byUser = false, now)
        policy.cancel()

        call(false)
        assertThat(runFor(5_000)).isEmpty()
    }

    @Test
    fun `starting to play while the call profile is on pauses at once`() {
        call(true)

        assertThat(policy.onPlayer(audible = true, byUser = false, now)).isEqualTo(Action.PAUSE)
    }

    private companion object {
        const val SETTLE = 1_000L
        const val MAX = 10 * 60_000L
    }
}
