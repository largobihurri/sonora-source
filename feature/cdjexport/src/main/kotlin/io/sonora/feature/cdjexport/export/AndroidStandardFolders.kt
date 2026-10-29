package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.export.StickFileSystem

/**
 * The folders Android's media provider creates at the root of every new removable volume (Alarms, DCIM, Music, ...),
 * and the `Android` folder apps get there. They mean nothing to a player and clutter its browser, so an export removes
 * the ones that hold nothing but Android's own marker files (`.database_uuid`, `.nomedia`).
 */
internal object AndroidStandardFolders {

    /** Exactly the names Android uses, compared as written; nothing else is ever removed. */
    val NAMES: Set<String> = setOf(
        "Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music", "Notifications", "Pictures",
        "Podcasts", "Recordings", "Ringtones", "LOST.DIR", ".trash-storage",
    )

    /** The folders that may be removed: [NAMES] and the `Android` folder of apps' data on the volume. */
    val CANDIDATES: Set<String> = NAMES + "Android"

    /**
     * Files Android leaves in those folders by itself (the media scanner's thumbnail database id, "no media here"),
     * exactly these names; they do not keep a folder. Anything else does.
     */
    private val MARKERS = setOf(".database_uuid", ".nomedia")

    /** A marker larger than this is not Android's (`.database_uuid` holds a UUID, `.nomedia` nothing). */
    private const val MAX_MARKER_BYTES = 64L

    /** Deeper than this, a folder is kept as if it held files. */
    private const val MAX_DEPTH = 16

    /**
     * Deletes the [CANDIDATES] folders at the root of [stick] that hold no file but Android's markers at any depth (empty
     * subfolders and markers go with them) and returns their names. Blocking and best effort, whatever the failure: a
     * folder that cannot be listed or deleted, or a marker whose size cannot be read, keeps its folder, and nothing
     * here ever stops an export.
     */
    fun removeEmpty(stick: StickFileSystem): List<String> {
        val candidates = try {
            stick.list("").orEmpty().filter { it.isFolder && it.name in CANDIDATES }.map { it.name }
        } catch (e: Exception) {
            return emptyList()
        }
        return candidates.filter { name ->
            try {
                !holdsFiles(stick, name, depth = 0) && stick.delete(name)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Whether the folder at [path] holds a file other than a marker at any depth; one that cannot be listed counts as
     * holding files.
     */
    private fun holdsFiles(stick: StickFileSystem, path: String, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        val entries = stick.list(path) ?: return true
        return entries.any {
            val child = "$path/${it.name}"
            if (it.isFolder) holdsFiles(stick, child, depth + 1) else !isMarker(stick, child, it.name)
        }
    }

    private fun isMarker(stick: StickFileSystem, path: String, name: String): Boolean {
        if (name !in MARKERS) return false
        val size = stick.sizeOf(path) ?: return false
        return size <= MAX_MARKER_BYTES
    }
}
