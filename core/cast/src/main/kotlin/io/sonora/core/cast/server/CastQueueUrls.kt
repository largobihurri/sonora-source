package io.sonora.core.cast.server

/** A file the cast server may send: a queue entry's audio or its artwork. */
sealed interface Served {
    /** The audio at [uri] (a SAF `content://` URI), sent as [mimeType]. */
    data class Audio(val uri: String, val mimeType: String) : Served

    /** The artwork at [uri] (`content://` or a `file://` URI in the app's artwork cache). */
    data class Artwork(val uri: String) : Served
}

/**
 * The queue -> URL mapping of one cast session. Each queue entry (by its media id, unique per entry) gets a number, and
 * the receiver is handed `http://<host>:<port>/<token>/audio/<n>` and `…/art/<n>`; a request path [resolve]s back to
 * the file only with the session's [token] and only while the entry is in the queue, so the server never serves
 * anything else from the phone.
 *
 * [retain] drops the entries the receiver's queue no longer holds. The receiver reports its queue asynchronously, so an
 * entry mapped less than [GRACE_MS] ago is kept even when a report lacks it (the report may predate it), and an empty
 * report (a receiver clears its queue for a moment while it loads another) drops nothing.
 *
 * Thread-safe: the player maps entries on the main thread while the server resolves paths on its own threads.
 */
class CastQueueUrls(host: String, port: Int, private val token: CastToken, private val now: () -> Long) {

    /** What the receiver is told about one entry. */
    data class Urls(val audio: String, val artwork: String?)

    private class Entry(val id: Int, val audioUri: String, val audioMime: String, val artworkUri: String?, val mappedAtMs: Long)

    private val origin = "http://$host:$port"
    private val base = "$origin/${token.value}"
    private val entries = HashMap<String, Entry>()
    private val byId = HashMap<Int, Entry>()
    private var nextId = 1

    /** The URLs for the entry [key] (its media id); the same entry keeps its number while it stays mapped. */
    @Synchronized
    fun map(key: String, audioUri: String, audioMime: String, artworkUri: String?): Urls {
        val known = entries[key]
        val entry = Entry(known?.id ?: nextId++, audioUri, audioMime, artworkUri, now())
        entries[key] = entry
        byId[entry.id] = entry
        return Urls("$base/$AUDIO/${entry.id}", artworkUri?.let { "$base/$ART/${entry.id}" })
    }

    /** The file a request for [path] (`/<token>/<kind>/<n>`, any query ignored) may receive, or null. */
    @Synchronized
    fun resolve(path: String): Served? {
        val segments = path.substringBefore('?').removePrefix("/").split('/')
        if (segments.size != 3 || !token.matches(segments[0])) return null
        val entry = segments[2].takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.let(byId::get) ?: return null
        return when (segments[1]) {
            AUDIO -> Served.Audio(entry.audioUri, entry.audioMime)
            ART -> entry.artworkUri?.let(Served::Artwork)
            else -> null
        }
    }

    /** The audio file behind one of this session's audio [url]s, or null for any other URL. */
    fun originalAudioUri(url: String): String? {
        if (!url.startsWith("$base/")) return null
        return (resolve(url.removePrefix(origin)) as? Served.Audio)?.uri
    }

    /** Keeps only the entries in [keys] (the receiver's queue), plus those mapped in the last [GRACE_MS]. */
    @Synchronized
    fun retain(keys: Set<String>) {
        if (keys.isEmpty()) return
        val cutoff = now() - GRACE_MS
        val gone = entries.filter { (key, entry) -> key !in keys && entry.mappedAtMs <= cutoff }
        for ((key, entry) in gone) {
            entries.remove(key)
            byId.remove(entry.id)
        }
    }

    companion object {
        /** How long a newly mapped entry is served before the receiver's queue must list it. */
        const val GRACE_MS = 30_000L

        private const val AUDIO = "audio"
        private const val ART = "art"
    }
}
