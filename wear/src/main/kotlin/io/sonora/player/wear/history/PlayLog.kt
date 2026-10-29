package io.sonora.player.wear.history

import java.io.File

/**
 * One play: the song ([trackId] is the Kora id of the song, `koraTrackId` of its file name, never a path), when the sound
 * first started and how long it was actually heard. A play is identified by ([trackId], [startedAtMs]); recording it again
 * with a longer [listenedMs] extends it.
 */
internal data class PlayLogEntry(val trackId: String, val startedAtMs: Long, val listenedMs: Long)

/** What the watch has played, kept only so `media.history` can answer; nothing on the watch shows it. */
internal interface PlayLog {
    /** Adds the play, or replaces the same play (same song and start) with this longer record. */
    fun record(entry: PlayLogEntry)

    /** The plays that started in [fromMs]..[toMs], both inclusive, oldest first. */
    fun between(fromMs: Long, toMs: Long): List<PlayLogEntry>
}

/**
 * [PlayLog] in one small append-only text file, `trackId TAB startedAtMs TAB listenedMs` per line.
 *
 * - Extending a play appends the new record; on load, a later line for the same play replaces the earlier one. A line
 *   cut by a crash, or any malformed line, is skipped, so the rest of the log survives.
 * - Retention is bounded twice: plays that started more than [maxAgeMs] ago are dropped, and at most [maxPlays] are kept
 *   (the oldest go first). The count is trimmed in batches of a tenth, so the file is not rewritten on every play.
 * - The file is compacted (rewritten with one line per play, through a temporary file and a rename) when something was
 *   dropped or when superseded lines outnumber the plays, so it stays within a few times [maxPlays] lines.
 * - Calls are synchronized and do file I/O, so call them off the main thread.
 */
internal class FilePlayLog(
    private val file: File,
    private val maxPlays: Int = DEFAULT_MAX_PLAYS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlayLog {
    private val plays = HashMap<Pair<String, Long>, PlayLogEntry>()
    private var lines = 0
    private var loaded = false

    @Synchronized
    override fun record(entry: PlayLogEntry) {
        load()
        val isNew = plays.put(entry.trackId to entry.startedAtMs, entry) == null
        append(entry)
        val dropped = isNew && prune()
        if (dropped || lines > 2 * plays.size + SLACK_LINES) compact()
    }

    @Synchronized
    override fun between(fromMs: Long, toMs: Long): List<PlayLogEntry> {
        load()
        return plays.values.filter { it.startedAtMs in fromMs..toMs }.sortedWith(compareBy({ it.startedAtMs }, { it.trackId }))
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val text = file.readText()
        text.lineSequence().forEach { line ->
            parse(line)?.let { plays[it.trackId to it.startedAtMs] = it }
            if (line.isNotEmpty()) lines++
        }
        // A crash can cut the last line short; end it so the next append starts on a line of its own.
        if (text.isNotEmpty() && !text.endsWith('\n')) file.appendText("\n")
        if (prune()) compact()
    }

    private fun parse(line: String): PlayLogEntry? {
        val parts = line.split('\t')
        if (parts.size != 3 || parts[0].isEmpty()) return null
        val startedAt = parts[1].toLongOrNull() ?: return null
        val listened = parts[2].toLongOrNull() ?: return null
        return PlayLogEntry(parts[0], startedAt, listened)
    }

    private fun append(entry: PlayLogEntry) {
        file.parentFile?.mkdirs()
        file.appendText(format(entry))
        lines++
    }

    private fun format(entry: PlayLogEntry) = "${entry.trackId}\t${entry.startedAtMs}\t${entry.listenedMs}\n"

    /** Drops the expired plays and, past the cap, the oldest ones. Returns whether anything went. */
    private fun prune(): Boolean {
        val before = plays.size
        val cutoff = clock() - maxAgeMs
        plays.values.removeAll { it.startedAtMs < cutoff }
        if (plays.size > maxPlays) {
            val keep = maxPlays - maxPlays / TRIM_FRACTION
            plays.values.sortedWith(compareBy({ it.startedAtMs }, { it.trackId }))
                .take(plays.size - keep)
                .forEach { plays.remove(it.trackId to it.startedAtMs) }
        }
        return plays.size != before
    }

    private fun compact() {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(plays.values.sortedBy { it.startedAtMs }.joinToString("") { format(it) })
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        lines = plays.size
    }

    companion object {
        /** The most plays kept: a play a minute for a day and a half, far more than a watch ever plays in 90 days. */
        const val DEFAULT_MAX_PLAYS = 5000

        /** The oldest play kept, by when it started. */
        const val DEFAULT_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        private const val SLACK_LINES = 100
        private const val TRIM_FRACTION = 10
    }
}
