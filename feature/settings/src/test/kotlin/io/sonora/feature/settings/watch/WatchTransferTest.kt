package io.sonora.feature.settings.watch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchport.WatchBusyException
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.sender.FailedTrack
import io.sonora.core.watchsync.sender.SendCandidate
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.core.watchsync.sender.SendProgressMath
import io.sonora.core.watchsync.sender.SendReport
import io.sonora.core.watchsync.sender.SentTrack
import java.io.IOException
import org.junit.Test

class WatchTransferTest {

    private fun progress(phase: SendProgress.Phase = SendProgress.Phase.SENDING, fraction: Float = 0.5f, overall: Float = 0.25f) =
        SendProgress(phase, trackNumber = 2, trackCount = 8, trackId = "id", title = "Halo", phaseFraction = fraction, overallFraction = overall)

    private fun sent(id: String, bytes: Long, ms: Long) = SentTrack(id, "Title $id", bytes, bytes, ms, resumedFrom = 0)

    private fun candidate(id: String) = SendCandidate(
        id = id, sourceUri = "content://$id", title = "Title $id", artist = "A", albumArtist = null, album = "B",
        trackNumber = 1, discNumber = 1, durationMs = 1_000, sourceVersion = 1,
    )

    @Test
    fun `idle stays idle`() {
        assertThat(WatchTransfers.of(WatchSyncState.Idle)).isEqualTo(WatchTransfer.Idle)
    }

    @Test
    fun `before the first song starts it is connecting`() {
        val running = WatchTransfers.of(WatchSyncState.Running(progress = null, songCount = 8))
        assertThat(running).isEqualTo(WatchTransfer.Running(songCount = 8, song = null, overallFraction = 0f))
    }

    @Test
    fun `a running send names the song and gives both its own and the overall progress`() {
        val running = WatchTransfers.of(WatchSyncState.Running(progress(SendProgress.Phase.SENDING, 0.5f, 0.25f), songCount = 8))
            as WatchTransfer.Running
        assertThat(running.overallFraction).isEqualTo(0.25f)
        val song = running.song!!
        assertThat(song.title).isEqualTo("Halo")
        assertThat(song.number).isEqualTo(2)
        assertThat(song.count).isEqualTo(8)
        assertThat(song.phase).isEqualTo(SendProgress.Phase.SENDING)
        assertThat(song.songFraction).isEqualTo(SendProgressMath.songFraction(SendProgress.Phase.SENDING, 0.5f))
    }

    @Test
    fun `a song being encoded counts only its share of the song`() {
        val running = WatchTransfers.of(WatchSyncState.Running(progress(SendProgress.Phase.ENCODING, 1f), songCount = 8)) as WatchTransfer.Running
        assertThat(running.song!!.songFraction).isEqualTo(SendProgressMath.ENCODE_SHARE)
    }

    @Test
    fun `out-of-range progress is kept within 0 to 1`() {
        val running = WatchTransfers.of(WatchSyncState.Running(progress(fraction = 1.7f, overall = -0.2f), songCount = 8)) as WatchTransfer.Running
        assertThat(running.overallFraction).isEqualTo(0f)
        assertThat(running.song!!.songFraction).isAtMost(1f)
    }

    @Test
    fun `a finished send counts what was sent, skipped and failed`() {
        val report = SendReport(
            sent = listOf(sent("1", 4_000_000, 2_000), sent("2", 6_000_000, 3_000)),
            skipped = listOf(candidate("3")),
            failed = listOf(FailedTrack("4", "Bad Song", SyncError.ENCODING_FAILED)),
            attempts = 2,
        )
        val done = WatchTransfers.of(WatchSyncState.Finished(report)) as WatchTransfer.Done
        assertThat(done.sentCount).isEqualTo(2)
        assertThat(done.alreadyThereCount).isEqualTo(1)
        assertThat(done.failed).containsExactly(FailedSong("Bad Song", SyncError.ENCODING_FAILED))
        assertThat(done.transferredBytes).isEqualTo(10_000_000)
        assertThat(done.bytesPerSecond).isEqualTo(2_000_000L)
    }

    @Test
    fun `a send that moved nothing has no speed`() {
        val report = SendReport(sent = emptyList(), skipped = listOf(candidate("1")), failed = emptyList(), attempts = 1)
        val done = WatchTransfers.of(WatchSyncState.Finished(report)) as WatchTransfer.Done
        assertThat(done.bytesPerSecond).isNull()
        assertThat(done.sentCount).isEqualTo(0)
    }

    @Test
    fun `a failed send carries its failure`() {
        val failed = WatchTransfers.of(WatchSyncState.Failed(WatchSyncFailure.NoWatch))
        assertThat(failed).isEqualTo(WatchTransfer.Failed(WatchSyncFailure.NoWatch))
    }

    // How a read of the watch maps to the connection state

    @Test
    fun `a missing watch, an old watch app and a dropped link are told apart`() {
        assertThat(WatchLinks.of(WatchNotFoundException())).isEqualTo(WatchLink.NO_WATCH)
        assertThat(WatchLinks.of(ProtocolException("version"))).isEqualTo(WatchLink.APP_OUTDATED)
        assertThat(WatchLinks.of(IOException("closed"))).isEqualTo(WatchLink.UNREACHABLE)
        assertThat(WatchLinks.of(IllegalStateException("?"))).isEqualTo(WatchLink.UNREACHABLE)
    }

    @Test
    fun `a read refused because a send runs means the watch is being sent to`() {
        assertThat(WatchLinks.of(WatchBusyException())).isEqualTo(WatchLink.SENDING)
    }
}
