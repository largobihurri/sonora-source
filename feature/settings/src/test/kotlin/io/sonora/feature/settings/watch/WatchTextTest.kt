package io.sonora.feature.settings.watch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.feature.settings.XmlStrings
import org.junit.Test

class WatchTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    private val nbsp = " "
    private val gib = 1024L * 1024 * 1024

    private fun space(totalGib: Long = 23, freeGib: Long = 9, usableGib: Long = 8, songsBytes: Long = 340L * 1024 * 1024, songs: Int = 3) =
        WatchSpace(
            totalBytes = totalGib * gib, freeBytes = freeGib * gib, usableBytes = usableGib * gib, songsBytes = songsBytes, songCount = songs,
        )

    private fun song(phase: SendProgress.Phase = SendProgress.Phase.SENDING) =
        CurrentSong("Halo", number = 2, count = 8, phase = phase, phaseFraction = 0.5f, songFraction = 0.65f)

    private fun group(album: String? = "Bandido", artist: String? = "Myke Towers", various: Boolean = false, songs: Int = 2, size: Long = 2048) =
        WatchAlbumGroup(
            album = album, artist = artist, variousArtists = various,
            songs = List(songs) { ManifestTrack("id$it", "T$it", durationMs = 1, sizeBytes = size / songs, sha256 = "0".repeat(64)) },
        )

    @Test
    fun `sizes and space follow the language`() {
        assertThat(WatchText.size(2048, en)).isEqualTo("2.0${nbsp}KiB")
        assertThat(WatchText.size(2048, es)).isEqualTo("2,0${nbsp}KiB")
        assertThat(WatchText.spaceCaption(space(), en)).isEqualTo("14.0${nbsp}GiB used of 23.0${nbsp}GiB")
        assertThat(WatchText.spaceCaption(space(), es)).isEqualTo("14,0${nbsp}GiB usados de 23,0${nbsp}GiB")
        assertThat(WatchText.spaceCaption(space(), eu)).contains("erabilita")
    }

    @Test
    fun `the songs figure says how many and how big`() {
        assertThat(WatchText.songsValue(space(songs = 3), en)).isEqualTo("3 songs · 340.0${nbsp}MiB")
        assertThat(WatchText.songsValue(space(songs = 1), en)).startsWith("1 song · ")
        assertThat(WatchText.songsValue(space(songs = 1), es)).startsWith("1 canción · ")
        assertThat(WatchText.songsValue(space(songs = 0, songsBytes = 0), en)).isEqualTo("No songs")
    }

    @Test
    fun `a fraction reads as a percentage`() {
        assertThat(WatchText.percent(0.42f, en)).isEqualTo("42%")
        assertThat(WatchText.percent(1f, en)).isEqualTo("100%")
        assertThat(WatchText.percent(0.5f, es)).contains("50")
        assertThat(WatchText.percent(-1f, en)).isEqualTo("0%")
    }

    @Test
    fun `the running send names its position and what it is doing`() {
        assertThat(WatchText.songPosition(song(), en)).isEqualTo("Song 2 of 8")
        assertThat(WatchText.songPosition(song(), es)).isEqualTo("Canción 2 de 8")
        assertThat(WatchText.phase(song(SendProgress.Phase.ENCODING), en)).isEqualTo("Converting to Opus")
        assertThat(WatchText.phase(song(SendProgress.Phase.SENDING), en)).isEqualTo("Sending to the watch")
        assertThat(WatchText.phase(song(SendProgress.Phase.SENDING), eu)).isNotEqualTo(WatchText.phase(song(SendProgress.Phase.ENCODING), eu))
        assertThat(WatchText.overall(0.25f, en)).isEqualTo("Overall: 25%")
        assertThat(WatchText.connecting(en)).isEqualTo("Connecting to the watch…")
    }

    @Test
    fun `the result lists what was sent, what was already there and what failed`() {
        val done = WatchTransfer.Done(sentCount = 2, alreadyThereCount = 1, failed = listOf(FailedSong("X", SyncError.ENCODING_FAILED)), transferredBytes = 0, bytesPerSecond = null)
        assertThat(WatchText.doneLines(done, en)).containsExactly("2 songs sent", "1 song was already on the watch", "1 song failed").inOrder()
        val one = WatchTransfer.Done(sentCount = 1, alreadyThereCount = 3, failed = emptyList(), transferredBytes = 0, bytesPerSecond = null)
        assertThat(WatchText.doneLines(one, en)).containsExactly("1 song sent", "3 songs were already on the watch").inOrder()
    }

    @Test
    fun `a result with nothing in it says so`() {
        val nothing = WatchTransfer.Done(0, 0, emptyList(), 0, null)
        assertThat(WatchText.doneLines(nothing, en)).containsExactly("Nothing was sent")
    }

    @Test
    fun `the speed line needs bytes and a speed`() {
        val moved = WatchTransfer.Done(1, 0, emptyList(), transferredBytes = 10L * 1024 * 1024, bytesPerSecond = 2L * 1024 * 1024)
        assertThat(WatchText.speedLine(moved, en)).isEqualTo("10.0${nbsp}MiB at 2.0${nbsp}MiB/s")
        assertThat(WatchText.speedLine(moved.copy(bytesPerSecond = null), en)).isNull()
        assertThat(WatchText.speedLine(moved.copy(transferredBytes = 0), en)).isNull()
    }

    @Test
    fun `every way a send fails has its own words in every language`() {
        val failures = listOf(
            WatchSyncFailure.NoWatch,
            WatchSyncFailure.WatchAppIncompatible,
            WatchSyncFailure.NotEnoughSpace(neededBytes = 2048, availableBytes = 1024),
            WatchSyncFailure.ConnectionLost,
            WatchSyncFailure.NothingToSend,
            WatchSyncFailure.Unexpected("boom"),
        )
        for (strings in listOf(en, es, eu)) {
            val words = failures.map { WatchText.failure(it, strings) }
            assertThat(words).containsNoDuplicates()
            assertThat(words.none { it.isBlank() }).isTrue()
        }
        assertThat(WatchText.failure(WatchSyncFailure.NotEnoughSpace(2048, 1024), en))
            .isEqualTo("The songs need 2.0${nbsp}KiB but the watch has room for 1.0${nbsp}KiB.")
        assertThat(WatchText.failure(WatchSyncFailure.Unexpected("boom"), en)).doesNotContain("boom")
    }

    @Test
    fun `a failed song names the song and the reason`() {
        assertThat(WatchText.failedSong(FailedSong("Bad Song", SyncError.ENCODING_FAILED), en)).isEqualTo("Bad Song — couldn’t convert it")
        for (strings in listOf(en, es, eu)) {
            val reasons = SyncError.entries.map { WatchText.failedSong(FailedSong("S", it), strings) }
            assertThat(reasons).containsNoDuplicates()
        }
    }

    @Test
    fun `every connection state has a title, and the problems have advice`() {
        for (strings in listOf(en, es, eu)) {
            assertThat(WatchLink.entries.map { WatchText.link(it, strings) }).containsNoDuplicates()
            for (link in listOf(WatchLink.NO_WATCH, WatchLink.APP_OUTDATED, WatchLink.UNREACHABLE)) {
                assertThat(WatchText.linkAdvice(link, strings)).isNotNull()
            }
            assertThat(WatchText.linkAdvice(WatchLink.CONNECTED, strings)).isNull()
        }
        assertThat(WatchText.link(WatchLink.CONNECTED, en)).isEqualTo("Connected")
        assertThat(WatchText.link(WatchLink.NO_WATCH, en)).isEqualTo("No watch found")
    }

    @Test
    fun `an album group is named by its album and described by artist, songs and size`() {
        assertThat(WatchText.groupTitle(group(), en)).isEqualTo("Bandido")
        assertThat(WatchText.groupTitle(group(album = null), en)).isEqualTo("No Album")
        assertThat(WatchText.groupSubtitle(group(), en)).isEqualTo("Myke Towers · 2 songs · 2.0${nbsp}KiB")
        assertThat(WatchText.groupSubtitle(group(artist = null, various = true), en)).isEqualTo("Various Artists · 2 songs · 2.0${nbsp}KiB")
        assertThat(WatchText.groupSubtitle(group(artist = null), en)).isEqualTo("2 songs · 2.0${nbsp}KiB")
    }

    @Test
    fun `a song row shows its title and size`() {
        val track = ManifestTrack("id", "Halo", durationMs = 200_000, sizeBytes = 3L * 1024 * 1024, sha256 = "0".repeat(64))
        assertThat(WatchText.songSubtitle(track, en)).isEqualTo("3:20 · 3.0${nbsp}MiB")
    }

    @Test
    fun `removing asks with the number of songs`() {
        assertThat(WatchText.removeTitle(1, en)).isEqualTo("Remove 1 Song from the Watch?")
        assertThat(WatchText.removeTitle(12, en)).isEqualTo("Remove 12 Songs from the Watch?")
        assertThat(WatchText.removeTitle(12, es)).isEqualTo("¿Quitar 12 canciones del reloj?")
    }

    @Test
    fun `every removal outcome has its words`() {
        for (strings in listOf(en, es, eu)) {
            val words = listOf(
                WatchMessage.Removed(3),
                WatchMessage.PartlyRemoved(removed = 1, requested = 3),
                WatchMessage.RemoveFailed,
            ).map { WatchText.message(it, strings) }
            assertThat(words).containsNoDuplicates()
        }
        assertThat(WatchText.message(WatchMessage.Removed(3), en)).isEqualTo("3 songs removed from the watch.")
        assertThat(WatchText.message(WatchMessage.PartlyRemoved(1, 3), en)).isEqualTo("Removed 1 of 3 songs. The rest are still on the watch.")
    }
}
