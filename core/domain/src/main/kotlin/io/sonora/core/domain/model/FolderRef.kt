package io.sonora.core.domain.model

/**
 * A directory of the library's folder tree. [treeUri] names the library folder it belongs to, [path] is the
 * directory's '/'-separated path as stored in [Track.folderPath], starting with the library folder's display path
 * (so a library folder itself is `FolderRef(treeUri, displayName)`). Same-named directories under different
 * library folders differ by [treeUri].
 */
data class FolderRef(val treeUri: String, val path: String) {
    /** Last path segment, e.g. `Rock` for `Music/Rock`. */
    val name: String get() = path.substringAfterLast('/')

    fun child(name: String) = FolderRef(treeUri, "$path/$name")
}

/** A folder in a listing; [trackCount] counts its own tracks and every subfolder's. */
data class FolderEntry(val folder: FolderRef, val trackCount: Int)

/** What one folder screen shows: subfolders first (by name), then the folder's own tracks (in play order). */
data class FolderContents(
    val folder: FolderRef,
    val subfolders: List<FolderEntry>,
    val tracks: List<Track>,
) {
    /** Every track in the folder, subfolders included. */
    val trackCount: Int get() = tracks.size + subfolders.sumOf { it.trackCount }
}
