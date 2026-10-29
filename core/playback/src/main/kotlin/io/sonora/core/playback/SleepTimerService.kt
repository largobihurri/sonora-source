package io.sonora.core.playback

import android.os.SystemClock
import io.sonora.core.domain.playback.SleepCountdown
import io.sonora.core.domain.playback.SleepFade
import io.sonora.core.domain.playback.SleepPlayhead
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The player as the sleep timer drives it (the service's player; a fake in tests). Main thread only. */
internal interface SleepPlayback {
    /** The player's own volume factor (0..1), on top of the system volume. */
    var volume: Float

    fun playhead(): SleepPlayhead

    fun pause()

    /** Whether playback pauses by itself at the end of the entry playing. */
    fun setPauseAtEndOfEntry(pause: Boolean)

    /** [listener] is told when playback paused at the end of an entry as [setPauseAtEndOfEntry] asked. */
    fun setOnEntryEnded(listener: (() -> Unit)?)
}

/**
 * The sleep timer, shared by the playback service (which [attach]es its player while it runs) and the UI (which
 * starts, extends and cancels it and shows [state]). The service runs in the app's process, so both hold this same
 * singleton, and the timer keeps counting with the app in the background.
 *
 * A clock timer runs out on [clock] (monotonic, so a change of the wall clock never moves it) and pauses playback; a
 * song or album timer asks the player to pause by itself at the end of the right entry, so it stops exactly there.
 * Over the last [SleepFade.DURATION_MS] the player's volume follows [SleepFade.gain], ticked every [FADE_TICK_MS];
 * otherwise the timer wakes once a second, on the second, to update [state]. The volume from before the fade comes
 * back [RESTORE_DELAY_MS] after the pause (once the paused output has gone quiet), and at once on a cancel, an
 * extension or the service stopping.
 *
 * Without the service (nothing to pause) a timer does not start. Everything runs on the service scope's (main)
 * thread.
 */
@Singleton
class SleepTimerService internal constructor(private val clock: () -> Long) : SleepTimer {

    @Inject
    constructor() : this(SystemClock::elapsedRealtime)

    private val mutableState = MutableStateFlow<SleepTimerState?>(null)
    override val state: StateFlow<SleepTimerState?> = mutableState.asStateFlow()

    private var playback: SleepPlayback? = null
    private var scope: CoroutineScope? = null
    private var countdown: SleepCountdown? = null
    private var ticking: Job? = null
    private var restoring: Job? = null

    /** The volume before the fade began; null while not fading. */
    private var fadeFrom: Float? = null

    /** The volume to put back after a timer ran out, until [RESTORE_DELAY_MS] have passed. */
    private var pendingRestore: Float? = null
    private var pausesAtEnd = false

    /** The service's player is here, driven on [scope] until [detach]. */
    internal fun attach(playback: SleepPlayback, scope: CoroutineScope) {
        detach()
        this.playback = playback
        this.scope = scope
        playback.setOnEntryEnded(::entryEnded)
    }

    /** The service is stopping: the timer goes, and the volume comes back now. */
    internal fun detach() {
        val playback = playback ?: return
        stop()
        playback.setOnEntryEnded(null)
        this.playback = null
        scope = null
    }

    override fun start(option: SleepTimerOption) {
        if (playback == null) return
        stop()
        countdown = SleepCountdown.start(option, clock())
        tick()
    }

    override fun extend(minutes: Int) {
        val playback = playback ?: return
        val running = countdown ?: return
        countdown = running.extendedBy(minutes, clock(), playback.playhead())
        tick()
    }

    override fun cancel() = stop()

    /** Updates the player and [state] now, and again when next due. */
    private fun tick() {
        ticking?.cancel()
        val next = update() ?: return
        ticking = scope?.launch {
            delay(next)
            ticking = null
            tick()
        }
    }

    /** Applies the countdown to the player; returns when to look again, or null when the timer ran out. */
    private fun update(): Long? {
        val playback = playback ?: return null
        val running = countdown ?: return null
        val now = clock()
        if (running.isDue(now)) {
            playback.pause()
            finish()
            return null
        }
        val playhead = playback.playhead()
        setPausesAtEnd(running.pausesAtEndOfCurrent(playhead))
        val remaining = running.remainingMs(now, playhead)
        applyGain(SleepFade.gain(remaining))
        mutableState.value = SleepTimerState(running.option, remaining?.let(::wholeSeconds))
        return nextTickIn(remaining)
    }

    /** Playback paused at the end of an entry: where a song or album timer ends. */
    private fun entryEnded() {
        if (countdown != null && pausesAtEnd) finish()
    }

    /** The timer ran out and playback is pausing: forget it, and bring the volume back once the output is quiet. */
    private fun finish() {
        val from = fadeFrom
        fadeFrom = null
        clear()
        if (from == null) return
        pendingRestore = from
        restoring = scope?.launch {
            delay(RESTORE_DELAY_MS)
            restoring = null
            restoreNow()
        }
    }

    /** Cancels any timer, restoring the volume now. */
    private fun stop() {
        restoreNow()
        clear()
    }

    private fun clear() {
        ticking?.cancel()
        ticking = null
        countdown = null
        setPausesAtEnd(false)
        mutableState.value = null
    }

    private fun applyGain(gain: Float) {
        val playback = playback ?: return
        if (gain >= 1f) {
            restoreNow()
            return
        }
        // A new fade right after a pause (a new timer) starts from the volume still to come back.
        if (fadeFrom == null && pendingRestore != null) restoreNow()
        val from = fadeFrom ?: playback.volume.also { fadeFrom = it }
        playback.volume = from * gain
    }

    /** Puts back the volume from before the fade, or the one a pending restore is to put back, now. */
    private fun restoreNow() {
        val from = fadeFrom ?: pendingRestore
        fadeFrom = null
        pendingRestore = null
        restoring?.cancel()
        restoring = null
        if (from != null) playback?.volume = from
    }

    private fun setPausesAtEnd(pause: Boolean) {
        if (pause == pausesAtEnd) return
        pausesAtEnd = pause
        playback?.setPauseAtEndOfEntry(pause)
    }

    internal companion object {
        /** After the pause, before the volume comes back: the paused output has gone quiet by then. */
        const val RESTORE_DELAY_MS = 500L

        /** How often the volume moves during the fade: steps far too small to hear. */
        const val FADE_TICK_MS = 50L

        private const val SECOND_MS = 1_000L

        /** [ms] rounded up to whole seconds, as a countdown shows it. */
        private fun wholeSeconds(ms: Long): Long = (ms + SECOND_MS - 1) / SECOND_MS * SECOND_MS

        /** On the next whole second of [remaining] or at the fade's start, then every [FADE_TICK_MS] through it. */
        private fun nextTickIn(remaining: Long?): Long = when {
            remaining == null -> SECOND_MS
            remaining > SleepFade.DURATION_MS -> minOf(remaining - SleepFade.DURATION_MS, (remaining - 1) % SECOND_MS + 1)
            remaining > 0L -> minOf(FADE_TICK_MS, remaining)
            else -> FADE_TICK_MS
        }
    }
}
