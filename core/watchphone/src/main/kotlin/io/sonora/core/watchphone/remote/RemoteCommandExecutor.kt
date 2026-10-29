package io.sonora.core.watchphone.remote

import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.VolumeStepLimiter
import io.sonora.core.watchsync.remote.moveTarget

/** Why a command was not carried out. */
enum class Rejection {
    NOTHING_PLAYING,

    /** The index or the id is not what the queue holds now: the watch's picture was a step behind. */
    STALE_QUEUE,

    /** The playing song cannot be removed from the remote. */
    PLAYING_SONG,

    /** The song cannot move that way (to or past the playing song, or off an end). */
    CANNOT_MOVE,

    NO_VOLUME_CHANGE,

    /** Subscriptions belong to the host. */
    NOT_A_PLAYER_COMMAND,
}

sealed interface CommandResult {
    data object Applied : CommandResult

    data class Rejected(val reason: Rejection) : CommandResult
}

/**
 * Maps the watch's commands onto the phone's [PlaybackController] port and [RemoteVolume]. Every queue command is checked
 * against the queue as it is when the command arrives: the index must be inside it and hold the entry with the command's
 * id. Nothing the watch sends is a file, a URI or a level. Main thread only (the controller's).
 */
class RemoteCommandExecutor(
    private val controller: PlaybackController,
    private val volume: RemoteVolume,
    private val limiter: VolumeStepLimiter,
) {
    fun execute(command: RemoteCommand): CommandResult {
        val state = controller.state.value
        return when (command) {
            is RemoteCommand.Subscribe, RemoteCommand.Unsubscribe -> CommandResult.Rejected(Rejection.NOT_A_PLAYER_COMMAND)
            is RemoteCommand.SetPlaying -> whenQueued(state) { if (command.playing) controller.play() else controller.pause() }
            RemoteCommand.Next -> whenQueued(state) { controller.skipToNext() }
            RemoteCommand.Previous -> whenQueued(state) { controller.skipToPrevious() }
            is RemoteCommand.VolumeSteps -> {
                val allowed = limiter.allow(command.steps)
                if (allowed == 0) {
                    CommandResult.Rejected(Rejection.NO_VOLUME_CHANGE)
                } else {
                    volume.step(allowed)
                    CommandResult.Applied
                }
            }
            is RemoteCommand.Jump -> ifQueued(state, command.index, command.id) {
                controller.skipToQueueItem(command.index)
                CommandResult.Applied
            }
            is RemoteCommand.Remove -> ifQueued(state, command.index, command.id) {
                if (command.index == state.currentIndex) {
                    CommandResult.Rejected(Rejection.PLAYING_SONG)
                } else {
                    controller.removeQueueItem(command.index)
                    CommandResult.Applied
                }
            }
            is RemoteCommand.Move -> ifQueued(state, command.index, command.id) {
                val target = moveTarget(command.direction, command.index, state.currentIndex, state.queue.size)
                if (target == null) {
                    CommandResult.Rejected(Rejection.CANNOT_MOVE)
                } else {
                    controller.moveQueueItem(command.index, target)
                    CommandResult.Applied
                }
            }
        }
    }

    private inline fun whenQueued(state: PlaybackState, action: () -> Unit): CommandResult {
        if (state.currentIndex !in state.queue.indices) return CommandResult.Rejected(Rejection.NOTHING_PLAYING)
        action()
        return CommandResult.Applied
    }

    private inline fun ifQueued(state: PlaybackState, index: Int, id: Long, action: () -> CommandResult): CommandResult =
        if (state.queue.getOrNull(index)?.id == id) action() else CommandResult.Rejected(Rejection.STALE_QUEUE)
}
