package io.sonora.core.data.archive.work

import androidx.work.Data
import androidx.work.WorkInfo
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.model.Track
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The archive jobs' pure parts: the in-memory passwords, the cache and its clean-up, and WorkManager data. */
class ArchiveJobPartsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val day = TimeUnit.DAYS.toMillis(1)

    // Passwords

    @Test
    fun `a password is held by job until removed, then wiped`() {
        val passwords = ArchivePasswords()
        val secret = "Correct-Horse-42".toCharArray()
        passwords.put("job-1", secret)

        assertThat(passwords.get("job-1")).isSameInstanceAs(secret)
        assertThat(passwords.get("job-2")).isNull()
        passwords.remove("job-1")
        assertThat(passwords.get("job-1")).isNull()
        assertThat(secret.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun `a new password for the same job wipes the old one`() {
        val passwords = ArchivePasswords()
        val old = "Old-Password-12".toCharArray()
        passwords.put("job", old)
        passwords.put("job", "New-Password-34".toCharArray())
        assertThat(old.all { it == '\u0000' }).isTrue()
        assertThat(String(passwords.get("job")!!)).isEqualTo("New-Password-34")
    }

    // Cache

    private fun jobFolder(cache: ArchiveCache, jobId: String, ageMs: Long, now: Long): File =
        cache.jobFolder(jobId).apply {
            mkdirs()
            val file = File(this, "Mix.zip").apply { writeText("zip") }
            file.setLastModified(now - ageMs)
            setLastModified(now - ageMs)
        }

    @Test
    fun `archives older than a day are removed, newer and running ones kept`() {
        val now = 10 * day
        val cache = ArchiveCache(temp.root)
        val old = jobFolder(cache, "old", day + 1, now)
        val fresh = jobFolder(cache, "fresh", day - 1, now)
        val running = jobFolder(cache, "running", 3 * day, now)

        cache.cleanUp(now, keep = setOf("running"))

        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(running.exists()).isTrue()
    }

    @Test
    fun `a folder counts as recent while any file in it is`() {
        val now = 10 * day
        val cache = ArchiveCache(temp.root)
        val folder = jobFolder(cache, "job", 2 * day, now)
        File(folder, "late.zip").apply { writeText("x") }.setLastModified(now - 1_000)

        cache.cleanUp(now)

        assertThat(folder.exists()).isTrue()
    }

    @Test
    fun `the expiry rule is strictly older than the age limit`() {
        assertThat(ArchiveCache.isExpired(lastActivityMs = 0, nowMs = day, maxAgeMs = day)).isFalse()
        assertThat(ArchiveCache.isExpired(lastActivityMs = 0, nowMs = day + 1, maxAgeMs = day)).isTrue()
    }

    @Test
    fun `a compression request survives the job file, password-free`() {
        val cache = ArchiveCache(temp.root)
        val track = Track(
            id = 7, uri = "content://p/tree/t/document/a.flac", title = "Intro", artist = "Band", album = "Record",
            albumArtist = "Band", durationMs = 61_000, trackNumber = 1, discNumber = 2, year = 2026, albumId = 3,
            artworkUri = null, mimeType = "audio/flac", sizeBytes = 123_456,
        )
        val request = CompressionRequest(ArchiveKind.ALBUM, "Record", listOf(track))

        cache.writeRequest("job", request)

        val read = cache.readRequest("job")!!
        assertThat(read.kind).isEqualTo(ArchiveKind.ALBUM)
        assertThat(read.name).isEqualTo("Record")
        val copy = read.tracks.single()
        assertThat(listOf(copy.uri, copy.title, copy.artist, copy.album, copy.albumArtist, copy.mimeType))
            .containsExactly(track.uri, track.title, track.artist, track.album, track.albumArtist, track.mimeType).inOrder()
        assertThat(listOf(copy.durationMs, copy.sizeBytes)).containsExactly(61_000L, 123_456L).inOrder()
        assertThat(listOf(copy.trackNumber, copy.discNumber)).containsExactly(1, 2).inOrder()
        assertThat(cache.readRequest("missing")).isNull()
        assertThat(cache.archiveFile("job", "Road: Trip").name).isEqualTo("Road_ Trip.zip")
        assertThat(cache.archiveFile("job", "x").parentFile).isEqualTo(cache.jobFolder("job"))
    }

    // WorkManager data

    @Test
    fun `progress round-trips through work data`() {
        val progress = ArchiveProgress(3, 12, 4_000_000_000, 9_000_000_000, "03 - Band - Song.flac")
        assertThat(ArchiveWork.progressOf(ArchiveWork.progressData(progress))).isEqualTo(progress)
        assertThat(ArchiveWork.progressOf(Data.EMPTY)).isNull()
    }

    @Test
    fun `every failure round-trips through work data`() {
        val failures = listOf(
            ArchiveFailure.PasswordRequired, ArchiveFailure.WrongPassword, ArchiveFailure.PasswordNeededAgain,
            ArchiveFailure.Damaged, ArchiveFailure.NoMusic, ArchiveFailure.NotEnoughSpace(5_000_000_000, 12),
            ArchiveFailure.UnreadableSource("Intro"), ArchiveFailure.DestinationUnwritable, ArchiveFailure.Unexpected,
        )
        for (failure in failures) {
            val data = ArchiveWork.resultData(ArchiveJobState.Failed(failure))
            assertThat(ArchiveWork.stateOf(WorkInfo.State.FAILED, data, Data.EMPTY)).isEqualTo(ArchiveJobState.Failed(failure))
        }
    }

    @Test
    fun `work states map to job states`() {
        val archive = CompressedArchive("/cache/archives/j/Mix.zip", "Mix.zip", 42, 2, encrypted = true)
        val compressed = ArchiveWork.resultData(ArchiveJobState.Compressed(archive))
        val extracted = ArchiveWork.resultData(ArchiveJobState.Extracted("content://folder", 9, 2))
        val progress = ArchiveProgress(1, 2, 10, 20, "a.flac")

        assertThat(ArchiveWork.stateOf(WorkInfo.State.ENQUEUED, Data.EMPTY, Data.EMPTY)).isEqualTo(ArchiveJobState.Queued)
        assertThat(ArchiveWork.stateOf(WorkInfo.State.BLOCKED, Data.EMPTY, Data.EMPTY)).isEqualTo(ArchiveJobState.Queued)
        assertThat(ArchiveWork.stateOf(WorkInfo.State.RUNNING, Data.EMPTY, ArchiveWork.progressData(progress)))
            .isEqualTo(ArchiveJobState.Running(progress))
        assertThat(ArchiveWork.stateOf(WorkInfo.State.RUNNING, Data.EMPTY, Data.EMPTY)).isEqualTo(ArchiveJobState.Running(null))
        assertThat(ArchiveWork.stateOf(WorkInfo.State.SUCCEEDED, compressed, Data.EMPTY)).isEqualTo(ArchiveJobState.Compressed(archive))
        assertThat(ArchiveWork.stateOf(WorkInfo.State.SUCCEEDED, extracted, Data.EMPTY))
            .isEqualTo(ArchiveJobState.Extracted("content://folder", 9, 2))
        assertThat(ArchiveWork.stateOf(WorkInfo.State.CANCELLED, Data.EMPTY, Data.EMPTY)).isEqualTo(ArchiveJobState.Cancelled)
        // A worker that crashed reports nothing.
        assertThat(ArchiveWork.stateOf(WorkInfo.State.FAILED, Data.EMPTY, Data.EMPTY))
            .isEqualTo(ArchiveJobState.Failed(ArchiveFailure.Unexpected))
    }

    @Test
    fun `the inputs carry no password`() {
        val compress = ArchiveWork.compressInput("job", encrypted = true)
        val extract = ArchiveWork.extractInput("job", "content://a.zip", "content://dest", encrypted = true)
        for (data in listOf(compress, extract)) {
            assertThat(data.keyValueMap.keys.none { "password" in it.lowercase() }).isTrue()
            assertThat(data.keyValueMap.values.none { it is CharArray }).isTrue()
        }
        assertThat(compress.getBoolean(ArchiveWork.KEY_ENCRYPTED, false)).isTrue()
    }

    @Test
    fun `progress is throttled to entry changes and an interval`() {
        val throttle = ProgressThrottle(intervalMs = 500)
        fun p(entries: Int, bytes: Long) = ArchiveProgress(entries, 10, bytes, 1_000)

        assertThat(throttle.admit(p(0, 1), nowMs = 1_000)).isTrue()
        assertThat(throttle.admit(p(0, 2), nowMs = 1_200)).isFalse()
        assertThat(throttle.admit(p(1, 3), nowMs = 1_300)).isTrue()
        assertThat(throttle.admit(p(1, 4), nowMs = 1_799)).isFalse()
        assertThat(throttle.admit(p(1, 5), nowMs = 1_800)).isTrue()
    }

    @Test
    fun `a burst of small entries is still rate limited`() {
        val throttle = ProgressThrottle(intervalMs = 500)
        fun p(entries: Int) = ArchiveProgress(entries, 1_000, entries.toLong(), 1_000)

        // A thousand tiny songs in one second: one report per entry would flood WorkManager and the notification.
        val admitted = (0 until 1_000).count { throttle.admit(p(it), nowMs = 10_000L + it) }

        assertThat(admitted).isAtMost(6)
        assertThat(throttle.admit(p(1_000), nowMs = 11_000)).isTrue()
    }
}
