package io.sonora.core.watchsync.sender

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.protocol.SyncMessage
import org.junit.Test

class SendPlannerTest {

    private fun candidate(id: String, durationMs: Long = 200_000) =
        SendCandidate(id = id, sourceUri = "content://media/$id", title = "Song $id", durationMs = durationMs)

    private fun held(id: String) =
        ManifestTrack(id = id, title = "x", durationMs = 1, sizeBytes = 1, sha256 = "a".repeat(64))

    private fun space(usable: Long) = SyncMessage.FreeSpace(freeBytes = usable + 100, totalBytes = 1_000_000_000, reserveBytes = 100)

    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val c = "cccccccccccccccc"

    @Test
    fun `everything is sent to an empty watch`() {
        val plan = SendPlanner.plan(listOf(candidate(a), candidate(b)), contents = emptyList(), space = space(1_000_000_000))

        assertThat(plan.toSend.map { it.id }).containsExactly(a, b).inOrder()
        assertThat(plan.alreadyOnWatch).isEmpty()
        assertThat(plan.estimatedBytes).isEqualTo(2 * OpusSizeEstimate.bytesFor(200_000))
        assertThat(plan.space).isEqualTo(SpaceCheck.Fits)
    }

    @Test
    fun `songs the watch already holds are left out and need no room`() {
        val plan = SendPlanner.plan(listOf(candidate(a), candidate(b), candidate(c)), contents = listOf(held(b)), space = space(1_000_000_000))

        assertThat(plan.toSend.map { it.id }).containsExactly(a, c).inOrder()
        assertThat(plan.alreadyOnWatch.map { it.id }).containsExactly(b)
        assertThat(plan.estimatedBytes).isEqualTo(2 * OpusSizeEstimate.bytesFor(200_000))
    }

    @Test
    fun `the same song twice in the selection is sent once`() {
        val plan = SendPlanner.plan(listOf(candidate(a), candidate(b), candidate(a)), contents = emptyList(), space = space(1_000_000_000))

        assertThat(plan.toSend.map { it.id }).containsExactly(a, b).inOrder()
    }

    @Test
    fun `when the estimate is more than the usable space it does not fit`() {
        val needed = OpusSizeEstimate.bytesFor(200_000)

        val plan = SendPlanner.plan(listOf(candidate(a)), contents = emptyList(), space = space(needed - 1))

        assertThat(plan.space).isEqualTo(SpaceCheck.NotEnough(neededBytes = needed, availableBytes = needed - 1))
    }

    @Test
    fun `an estimate exactly equal to the usable space fits`() {
        val needed = OpusSizeEstimate.bytesFor(200_000)

        assertThat(SendPlanner.plan(listOf(candidate(a)), emptyList(), space(needed)).space).isEqualTo(SpaceCheck.Fits)
    }

    @Test
    fun `nothing to send always fits`() {
        val plan = SendPlanner.plan(listOf(candidate(a)), contents = listOf(held(a)), space = space(0))

        assertThat(plan.toSend).isEmpty()
        assertThat(plan.space).isEqualTo(SpaceCheck.Fits)
    }

    @Test
    fun `the reserve is not usable`() {
        val needed = OpusSizeEstimate.bytesFor(200_000)
        val tight = SyncMessage.FreeSpace(freeBytes = needed + 50, totalBytes = 1_000_000_000, reserveBytes = 100)

        assertThat(SendPlanner.plan(listOf(candidate(a)), emptyList(), tight).space).isInstanceOf(SpaceCheck.NotEnough::class.java)
    }
}
