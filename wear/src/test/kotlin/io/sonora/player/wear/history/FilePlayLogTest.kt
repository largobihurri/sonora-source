package io.sonora.player.wear.history

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePlayLogTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = DAY * 1000
    private val file get() = File(folder.root, "plays.log")

    private fun log(maxPlays: Int = 5000, maxAgeMs: Long = 90 * DAY) = FilePlayLog(file, maxPlays, maxAgeMs) { now }

    private fun entry(track: String, at: Long, listened: Long = 1_000) = PlayLogEntry(track, at, listened)

    @Test
    fun `plays are listed oldest first inside an inclusive window`() {
        val log = log()
        log.record(entry("c", now - 10))
        log.record(entry("a", now - 30))
        log.record(entry("b", now - 20))
        log.record(entry("old", now - 90))

        assertThat(log.between(now - 30, now - 10).map { it.trackId }).containsExactly("a", "b", "c").inOrder()
        assertThat(log.between(now - 29, now - 11).map { it.trackId }).containsExactly("b")
        assertThat(log.between(now - 20, now - 20).map { it.trackId }).containsExactly("b")
    }

    @Test
    fun `recording the same play again extends it instead of adding one`() {
        val log = log()
        log.record(entry("a", now, 1_000))
        log.record(entry("a", now, 9_000))

        assertThat(log.between(0, Long.MAX_VALUE)).containsExactly(entry("a", now, 9_000))
    }

    @Test
    fun `plays survive a restart and the last extension wins`() {
        log().apply {
            record(entry("a", now - 100, 1_000))
            record(entry("a", now - 100, 5_000))
            record(entry("b", now - 50, 2_000))
        }

        val reopened = log()

        assertThat(reopened.between(0, Long.MAX_VALUE))
            .containsExactly(entry("a", now - 100, 5_000), entry("b", now - 50, 2_000)).inOrder()
    }

    @Test
    fun `a torn or corrupt line is skipped and the rest is kept`() {
        log().record(entry("a", now - 100, 1_000))
        file.appendText("garbage\n1\t2\n\tx\t3\nb\t${now - 50}\t")

        assertThat(log().between(0, Long.MAX_VALUE)).containsExactly(entry("a", now - 100, 1_000))
    }

    @Test
    fun `plays older than the retention age are dropped when a play is added`() {
        val log = log(maxAgeMs = 10 * DAY)
        log.record(entry("old", now - 11 * DAY))
        log.record(entry("fresh", now - DAY))

        assertThat(log.between(0, Long.MAX_VALUE).map { it.trackId }).containsExactly("fresh")
        assertThat(log().between(0, Long.MAX_VALUE).map { it.trackId }).containsExactly("fresh")
    }

    @Test
    fun `the log never keeps more than the cap, dropping the oldest`() {
        val log = log(maxPlays = 100)
        repeat(250) { log.record(entry("t$it", now - 1_000 + it)) }

        val kept = log.between(0, Long.MAX_VALUE)
        assertThat(kept.size).isAtMost(100)
        assertThat(kept.size).isAtLeast(90)
        assertThat(kept.last().trackId).isEqualTo("t249")
        assertThat(log().between(0, Long.MAX_VALUE)).isEqualTo(kept)
    }

    @Test
    fun `the file stays small however long one play is extended`() {
        val log = log()
        repeat(2_000) { log.record(entry("a", now, it + 1L)) }

        assertThat(file.readLines().size).isLessThan(500)
        assertThat(log().between(0, Long.MAX_VALUE).single().listenedMs).isEqualTo(2_000)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
