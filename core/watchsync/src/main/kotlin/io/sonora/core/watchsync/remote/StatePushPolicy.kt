package io.sonora.core.watchsync.remote

import kotlin.math.abs

/** Decides which states the phone pushes: changes, not the clock ticking (the watch moves the position on itself). */
object StatePushPolicy {
    /**
     * Whether [next] (taken at [now]) differs from [previous] (pushed at [previousAt]) in anything but the position moving
     * on as playing does. A position far from where playing would have put it (a seek, a restart) counts.
     */
    fun needsPush(previous: RemoteState?, previousAt: Long, next: RemoteState, now: Long): Boolean {
        if (previous == null) return true
        if (previous.copy(positionMs = 0L) != next.copy(positionMs = 0L)) return true
        return abs(next.positionMs - previous.positionAt(now - previousAt)) > RemoteLimits.POSITION_JUMP_MS
    }
}
