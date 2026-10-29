package io.sonora.core.watchsync.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QueueDirection {
    @SerialName("up")
    Up,

    @SerialName("down")
    Down,
}

/**
 * Where the song at [index] goes when moved one place in [direction], in a queue of [count] songs whose playing song is at
 * [currentIndex]; `null` when it cannot move that way. Only upcoming songs move, and never to or past the playing song, so a
 * move never changes which song is playing; nothing moves off either end. The watch's own queue and the phone's remote use
 * the same rule.
 */
fun moveTarget(direction: QueueDirection, index: Int, currentIndex: Int, count: Int): Int? {
    if (index <= currentIndex || index >= count) return null
    val target = when (direction) {
        QueueDirection.Up -> index - 1
        QueueDirection.Down -> index + 1
    }
    return target.takeIf { it > currentIndex && it < count }
}
