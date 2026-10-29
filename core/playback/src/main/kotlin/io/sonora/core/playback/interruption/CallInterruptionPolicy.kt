package io.sonora.core.playback.interruption

/**
 * Decides when Sonora pauses for another app's call audio profile and when it resumes, like the big players do
 * during calls. An app that switches the device to a call mode (a VoIP call, voice dictation) without taking audio
 * focus gets every output moved to the call profile (Bluetooth SCO: mono, narrow-band, quiet); music played through
 * it is ruined, so Sonora pauses instead.
 *
 * - The call profile coming on while the player is audible pauses at once ([Action.PAUSE]). It is level-triggered:
 *   a player becoming audible while the profile is on (playback started, audio focus given back) pauses too.
 * - Once the profile has been off for [settleMs] (the Bluetooth A2DP link needs a moment to come back, or the music
 *   would resume into SCO), a pause of Sonora's own resumes ([Action.RESUME]). The profile coming back on within
 *   the delay restarts the wait, so a mode that flaps produces a single pause and a single resume.
 * - Only Sonora's own pause resumes, and only if nothing else happened: the user playing or pausing ([onPlayer] with
 *   `byUser`), a track change or a stop ([cancel]) drop it, as does an interruption that lasted [maxInterruptionMs]
 *   or more, or another app's media playing when the resume is due.
 * - The user playing on through a call is respected: Sonora does not pause again until that call ends.
 * - A call that takes audio focus is Media3's: the player is not audible then (playback suppressed), so nothing is
 *   paused here and Media3 resumes when focus comes back. Should focus come back while the call profile is still
 *   on, the player is paused here and resumed after the settle delay, once.
 *
 * Times are [Long] milliseconds on a monotonic clock. [tick] is called regularly while [isHolding]. Not thread-safe:
 * call it from the player's thread.
 */
internal class CallInterruptionPolicy(
    private val settleMs: Long = SETTLE_MS,
    private val maxInterruptionMs: Long = MAX_INTERRUPTION_MS,
) {
    enum class Action { NONE, PAUSE, RESUME }

    private var callActive = false
    private var audible = false

    /** The user chose to play during the current call: no more pauses until it ends. */
    private var userOverride = false

    /** When Sonora paused for the call, while that pause is to be undone. */
    private var pausedAt: Long? = null

    /** When the call profile went off, while a resume waits for the settle delay. */
    private var callEndedAt: Long? = null

    /** Sonora paused for a call and may resume: [tick] has to be called. */
    val isHolding: Boolean get() = pausedAt != null

    /** The call profile is ([active]) or is not on: a call mode, or music routed to a call device. */
    fun onCallProfile(active: Boolean, now: Long): Action {
        val wasActive = callActive
        callActive = active
        if (active) {
            callEndedAt = null
            return pauseIfAudible(now)
        }
        if (wasActive) {
            userOverride = false
            if (pausedAt != null) callEndedAt = now
        }
        return Action.NONE
    }

    /**
     * The player became [audible] (it wants to play and nothing holds it silent) or not; [byUser] when a controller
     * asked (play / pause), not the system (audio focus, becoming noisy). Sonora's own pause and resume are not reported.
     */
    fun onPlayer(audible: Boolean, byUser: Boolean, now: Long): Action {
        this.audible = audible
        if (!byUser) return pauseIfAudible(now)
        cancel()
        if (audible && callActive) userOverride = true
        return Action.NONE
    }

    /** The user changed track or stopped: Sonora's pause is no longer to be undone. */
    fun cancel() {
        pausedAt = null
        callEndedAt = null
    }

    /** Resumes once the call profile has been off for the settle delay, if Sonora's pause still stands. */
    fun tick(now: Long, otherMediaActive: Boolean): Action {
        val paused = pausedAt ?: return Action.NONE
        val ended = callEndedAt
        if ((ended ?: now) - paused >= maxInterruptionMs) {
            cancel()
            return Action.NONE
        }
        if (ended == null || now - ended < settleMs) return Action.NONE
        cancel()
        if (otherMediaActive) return Action.NONE
        audible = true
        return Action.RESUME
    }

    private fun pauseIfAudible(now: Long): Action {
        if (!callActive || !audible || userOverride || pausedAt != null) return Action.NONE
        pausedAt = now
        audible = false
        return Action.PAUSE
    }

    companion object {
        const val SETTLE_MS = 1_000L
        const val MAX_INTERRUPTION_MS = 10 * 60_000L
    }
}
