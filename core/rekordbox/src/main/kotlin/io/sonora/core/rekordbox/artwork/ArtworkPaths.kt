package io.sonora.core.rekordbox.artwork

import java.util.Locale

/**
 * Where a device library's cover pictures go, as rekordbox lays them out: picture `N` (the id of its row in the
 * `artwork` table, which tracks point to with `artwork_id`) is `/PIONEER/Artwork/<folder>/aN.jpg`, an 80x80 JPEG, with a
 * 240x240 copy at `aN_m.jpg` that newer players show (crate-digger's export analysis: the high-resolution path adds `_m`
 * before the extension). Folders hold twenty pictures each, `%05d` of `N / 20 + 1`: checked on every one of the 2178
 * artwork rows of rekordbox's export in rekordcrate's `data/pdb/num_rows`. Paths are from the stick root with a leading
 * `/`, as the `artwork` rows store them; drop it to write the file.
 */
object ArtworkPaths {
    /** Side of the picture the `artwork` row points to. */
    const val SMALL_PIXELS = 80

    /** Side of its high-resolution copy. */
    const val LARGE_PIXELS = 240

    private const val PER_FOLDER = 20

    /** The 80x80 picture of artwork [id], as its `artwork` row holds it. */
    fun small(id: Int): String = "${folder(id)}/a$id.jpg"

    /** The 240x240 copy of artwork [id]. */
    fun large(id: Int): String = "${folder(id)}/a${id}_m.jpg"

    private fun folder(id: Int): String {
        require(id > 0) { "artwork ids start at 1: $id" }
        return String.format(Locale.ROOT, "/PIONEER/Artwork/%05d", id / PER_FOLDER + 1)
    }
}
