package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.sender.FailedTrack
import io.sonora.core.watchsync.sender.SendCandidate
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.core.watchsync.sender.SendReport
import io.sonora.core.watchsync.sender.SentTrack
import io.sonora.feature.library.XmlStrings
import org.junit.Test

class WatchNoticeTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    private fun sent(id: String) = SentTrack(id, "Title $id", 1_000, 1_000, 100, resumedFrom = 0)

    private fun candidate(id: String) = SendCandidate(
        id = id, sourceUri = "content://$id", title = "Title $id", artist = "A", albumArtist = null, album = "B",
        trackNumber = 1, discNumber = 1, durationMs = 1_000, sourceVersion = 1,
    )

    private fun finished(sent: Int = 0, skipped: Int = 0, failed: Int = 0) = WatchSyncState.Finished(
        SendReport(
            sent = List(sent) { sent("s$it") },
            skipped = List(skipped) { candidate("k$it") },
            failed = List(failed) { FailedTrack("f$it", "Bad $it", SyncError.ENCODING_FAILED) },
            attempts = 1,
        ),
    )

    @Test
    fun `starting names what is being sent`() {
        assertThat(WatchNotice.started("Bandido", en)).isEqualTo("Sending “Bandido” to your watch")
        assertThat(WatchNotice.started("Bandido", es)).isEqualTo("Enviando «Bandido» a tu reloj")
        assertThat(WatchNotice.busy(en)).isEqualTo("A transfer to the watch is already running")
    }

    @Test
    fun `a clean send says how many songs arrived`() {
        assertThat(WatchNotice.ended(finished(sent = 1), en)).isEqualTo("1 song sent to your watch")
        assertThat(WatchNotice.ended(finished(sent = 12, skipped = 3), en)).isEqualTo("12 songs sent to your watch")
        assertThat(WatchNotice.ended(finished(sent = 12), es)).isEqualTo("12 canciones enviadas a tu reloj")
    }

    @Test
    fun `a send of songs the watch already holds says so`() {
        assertThat(WatchNotice.ended(finished(skipped = 4), en)).isEqualTo("Already on your watch")
    }

    @Test
    fun `a send with failed songs points to the Watch page`() {
        assertThat(WatchNotice.ended(finished(sent = 5, failed = 2), en)).isEqualTo("5 sent, 2 failed. Details are in Settings › Watch.")
    }

    @Test
    fun `every way a send stops has its own notice in every language`() {
        val failures = listOf(
            WatchSyncFailure.NoWatch,
            WatchSyncFailure.WatchAppIncompatible,
            WatchSyncFailure.NotEnoughSpace(2, 1),
            WatchSyncFailure.ConnectionLost,
            WatchSyncFailure.NothingToSend,
            WatchSyncFailure.Unexpected("boom"),
        )
        for (strings in listOf(en, es, eu)) {
            val notices = failures.map { WatchNotice.ended(WatchSyncState.Failed(it), strings) }
            assertThat(notices).containsNoDuplicates()
            assertThat(notices).doesNotContain(null)
        }
        assertThat(WatchNotice.ended(WatchSyncState.Failed(WatchSyncFailure.NoWatch), en)).isEqualTo("No watch with Sonora is connected")
        assertThat(WatchNotice.ended(WatchSyncState.Failed(WatchSyncFailure.Unexpected("boom")), en)).doesNotContain("boom")
    }

    @Test
    fun `a send that is still going has no ending notice`() {
        assertThat(WatchNotice.ended(WatchSyncState.Idle, en)).isNull()
        assertThat(WatchNotice.ended(WatchSyncState.Running(null, 3), en)).isNull()
        val progress = SendProgress(SendProgress.Phase.SENDING, 1, 3, "id", "T", 0.5f, 0.2f)
        assertThat(WatchNotice.ended(WatchSyncState.Running(progress, 3), en)).isNull()
    }
}
