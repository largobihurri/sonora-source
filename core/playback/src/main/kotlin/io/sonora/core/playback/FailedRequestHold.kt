package io.sonora.core.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.ControllerInfo

/**
 * A car or voice request that fails leaves the queue as it was, but a Media3 controller sends its prepare() and play()
 * as commands of their own, queued behind the request: they would start the old queue. This remembers the controller
 * whose request just failed, so its next prepare and play are held back ([HoldingPlayer]). The hold ends with that
 * play, with a new request from the controller ([release]), or after [HOLD_MS]. Legacy controllers (Android Auto) are
 * never affected in practice: the session only prepares and plays for them when their request succeeds.
 */
internal class FailedRequestHold(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var controller: ControllerInfo? = null
    private var failedAt = 0L

    /** [controller]'s request failed just now. */
    fun failed(controller: ControllerInfo) {
        this.controller = controller
        failedAt = clock()
    }

    /** [controller] asks for something new: an earlier failure no longer holds its commands back. */
    fun release(controller: ControllerInfo) {
        if (controller == this.controller) this.controller = null
    }

    /** Whether [controller]'s command is held back; [ends] lets go of the hold (its play). */
    fun holds(controller: ControllerInfo?, ends: Boolean): Boolean {
        val held = this.controller ?: return false
        if (clock() - failedAt > HOLD_MS) {
            this.controller = null
            return false
        }
        if (controller != held) return false
        if (ends) this.controller = null
        return true
    }

    companion object {
        /** The queued commands run as soon as the request fails; anything later is a new press. */
        const val HOLD_MS = 1_000L
    }
}

/** The session's [player], whose prepare and play skip what [hold] holds back for the controller asking ([asking]). */
@OptIn(UnstableApi::class)
internal class HoldingPlayer(
    player: Player,
    private val hold: FailedRequestHold,
) : ForwardingPlayer(player) {

    /** The controller whose command the session is running; set once the session exists. */
    var asking: () -> ControllerInfo? = { null }

    override fun prepare() {
        if (!hold.holds(asking(), ends = false)) super.prepare()
    }

    override fun play() {
        if (!hold.holds(asking(), ends = true)) super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && hold.holds(asking(), ends = true)) return
        super.setPlayWhenReady(playWhenReady)
    }
}
