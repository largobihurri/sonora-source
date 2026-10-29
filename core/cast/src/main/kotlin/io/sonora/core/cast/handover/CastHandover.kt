package io.sonora.core.cast.handover

import java.net.InetAddress

/** The cast media server as the handover drives it: one server session per cast. */
interface CastServerControl {
    /** Starts serving on [address] with a new token; false when it cannot listen there. */
    fun start(address: InetAddress): Boolean

    /** Stops serving and forgets the session's URLs. */
    fun stop()
}

/** How playback lands back on the phone when a cast ends. */
enum class ToPhone {
    /** Take the receiver's queue, item and position, paused: a cast that ends never blares from the phone. */
    TAKE_CAST_STATE_PAUSED,

    /** The queue never reached a receiver (or there was no cast): the phone's player still holds it, untouched. */
    KEEP_PHONE_STATE,
}

/**
 * The local <-> cast handover, free of Media3 and the Cast SDK so it is JVM-testable. The player calls [toCast] right
 * before the queue moves to a receiver and [toPhone] right before it comes back; Wi-Fi monitoring calls [onWifiLost].
 *
 * - On the phone, [toCast] starts the media server on the Wi-Fi address. Without an address, or when the server cannot
 *   listen, the cast is aborted: the queue stays on the phone and the Cast session is ended through [endCastSession].
 * - While casting, losing Wi-Fi ends the session (once); the server keeps answering until the queue is back.
 * - [toPhone] stops the server; the queue comes back paused from a real cast, and is left alone after an aborted one.
 *
 * Confined to one thread (the main thread in production).
 */
class CastHandover(
    private val wifiAddress: () -> InetAddress?,
    private val server: CastServerControl,
    private val endCastSession: () -> Unit,
) {
    private enum class State { PHONE, CASTING, ENDING, ABORTED }

    private var state = State.PHONE

    /** True while the receiver plays the queue from this phone's server. */
    val isCasting: Boolean get() = state == State.CASTING || state == State.ENDING

    /** Returns true when the queue may move to the receiver (the server serves it), false to keep it here. */
    fun toCast(): Boolean {
        if (isCasting) return true
        val address = wifiAddress()
        if (address == null || !server.start(address)) {
            state = State.ABORTED
            endCastSession()
            return false
        }
        state = State.CASTING
        return true
    }

    fun toPhone(): ToPhone {
        val wasCasting = isCasting
        if (wasCasting) server.stop()
        state = State.PHONE
        return if (wasCasting) ToPhone.TAKE_CAST_STATE_PAUSED else ToPhone.KEEP_PHONE_STATE
    }

    /** The phone left the Wi-Fi network: the receiver can no longer reach the server, so the cast ends. */
    fun onWifiLost() {
        if (state != State.CASTING) return
        state = State.ENDING
        endCastSession()
    }

    /** The player is going away: stop serving whatever the state. */
    fun release() {
        if (isCasting) server.stop()
        state = State.PHONE
    }
}
