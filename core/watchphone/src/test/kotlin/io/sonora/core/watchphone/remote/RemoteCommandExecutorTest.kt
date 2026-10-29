package io.sonora.core.watchphone.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.VolumeStepLimiter
import org.junit.Test

class RemoteCommandExecutorTest {
    private val playback = FakePlayback(playbackState(count = 5, current = 1))
    private val volume = FakeVolume()
    private val executor = RemoteCommandExecutor(playback, volume, VolumeStepLimiter(now = { 0L }))

    private fun run(command: RemoteCommand) = executor.execute(command)

    @Test
    fun `play and pause go to the player`() {
        assertThat(run(RemoteCommand.SetPlaying(false))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.SetPlaying(true))).isEqualTo(CommandResult.Applied)

        assertThat(playback.calls).containsExactly("pause", "play").inOrder()
    }

    @Test
    fun `next and previous go to the player`() {
        run(RemoteCommand.Next)
        run(RemoteCommand.Previous)

        assertThat(playback.calls).containsExactly("next", "previous").inOrder()
    }

    @Test
    fun `transport does nothing while nothing is queued`() {
        playback.emit(playbackState(count = 0, current = -1))

        assertThat(run(RemoteCommand.SetPlaying(true))).isEqualTo(CommandResult.Rejected(Rejection.NOTHING_PLAYING))
        assertThat(run(RemoteCommand.Next)).isEqualTo(CommandResult.Rejected(Rejection.NOTHING_PLAYING))
        assertThat(run(RemoteCommand.Previous)).isEqualTo(CommandResult.Rejected(Rejection.NOTHING_PLAYING))
        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `jump needs the index and the id to agree with the queue now`() {
        assertThat(run(RemoteCommand.Jump(index = 3, id = 103))).isEqualTo(CommandResult.Applied)

        assertThat(playback.calls).containsExactly("skipTo(3)")
    }

    @Test
    fun `a jump built from an old picture of the queue does nothing`() {
        assertThat(run(RemoteCommand.Jump(index = 3, id = 104))).isEqualTo(CommandResult.Rejected(Rejection.STALE_QUEUE))
        assertThat(run(RemoteCommand.Jump(index = 9, id = 103))).isEqualTo(CommandResult.Rejected(Rejection.STALE_QUEUE))
        assertThat(run(RemoteCommand.Jump(index = -1, id = 100))).isEqualTo(CommandResult.Rejected(Rejection.STALE_QUEUE))
        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `an upcoming or played song can be removed, the playing one cannot`() {
        assertThat(run(RemoteCommand.Remove(index = 3, id = 103))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.Remove(index = 0, id = 100))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.Remove(index = 1, id = 101))).isEqualTo(CommandResult.Rejected(Rejection.PLAYING_SONG))
        assertThat(run(RemoteCommand.Remove(index = 3, id = 999))).isEqualTo(CommandResult.Rejected(Rejection.STALE_QUEUE))

        assertThat(playback.calls).containsExactly("remove(3)", "remove(0)").inOrder()
    }

    @Test
    fun `an upcoming song moves one place and the player is told where it lands`() {
        assertThat(run(RemoteCommand.Move(index = 3, id = 103, direction = QueueDirection.Down))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.Move(index = 3, id = 103, direction = QueueDirection.Up))).isEqualTo(CommandResult.Applied)

        assertThat(playback.calls).containsExactly("move(3,4)", "move(3,2)").inOrder()
    }

    @Test
    fun `nothing moves to or past the playing song, off the end, or from a stale index`() {
        val up = run(RemoteCommand.Move(index = 2, id = 102, direction = QueueDirection.Up))
        val down = run(RemoteCommand.Move(index = 4, id = 104, direction = QueueDirection.Down))
        val playing = run(RemoteCommand.Move(index = 1, id = 101, direction = QueueDirection.Down))
        val stale = run(RemoteCommand.Move(index = 3, id = 102, direction = QueueDirection.Down))

        assertThat(up).isEqualTo(CommandResult.Rejected(Rejection.CANNOT_MOVE))
        assertThat(down).isEqualTo(CommandResult.Rejected(Rejection.CANNOT_MOVE))
        assertThat(playing).isEqualTo(CommandResult.Rejected(Rejection.CANNOT_MOVE))
        assertThat(stale).isEqualTo(CommandResult.Rejected(Rejection.STALE_QUEUE))
        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `volume steps reach the phone's volume only through the limiter`() {
        assertThat(run(RemoteCommand.VolumeSteps(2))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.VolumeSteps(-1))).isEqualTo(CommandResult.Applied)
        assertThat(run(RemoteCommand.VolumeSteps(1_000))).isEqualTo(CommandResult.Applied)

        assertThat(volume.steps).containsExactly(2, -1, 4).inOrder() // 1000 is capped at 4 per message, inside the 6 per second budget
    }

    @Test
    fun `a volume request that changes nothing is rejected`() {
        assertThat(run(RemoteCommand.VolumeSteps(0))).isEqualTo(CommandResult.Rejected(Rejection.NO_VOLUME_CHANGE))
        run(RemoteCommand.VolumeSteps(4))
        run(RemoteCommand.VolumeSteps(2))
        assertThat(run(RemoteCommand.VolumeSteps(1))).isEqualTo(CommandResult.Rejected(Rejection.NO_VOLUME_CHANGE))
    }

    @Test
    fun `lowering the volume is always allowed`() {
        run(RemoteCommand.VolumeSteps(4))
        run(RemoteCommand.VolumeSteps(2))

        assertThat(run(RemoteCommand.VolumeSteps(-4))).isEqualTo(CommandResult.Applied)
    }

    @Test
    fun `subscriptions are the host's business, not the player's`() {
        assertThat(run(RemoteCommand.Subscribe())).isEqualTo(CommandResult.Rejected(Rejection.NOT_A_PLAYER_COMMAND))
        assertThat(run(RemoteCommand.Unsubscribe)).isEqualTo(CommandResult.Rejected(Rejection.NOT_A_PLAYER_COMMAND))
    }
}
