package io.sonora.feature.cdjexport.export

/**
 * Keeps the app's access to picked documents and trees beyond the picker's own grant. Read access is given up again as
 * soon as no picked track or running export needs it; see [OwnedReadGrants] for why that is bounded. Blocking.
 */
interface UriPermissions {
    /** Keeps read access to [uri]; `false` when it cannot be kept (no persistable access offered, or at the limit). */
    fun takeRead(uri: String): Boolean

    /** Keeps read and write access to the tree [treeUri] (the stick), one grant per stick volume. */
    fun takeReadWrite(treeUri: String): Boolean

    /** The trees the app holds persisted read and write access to (sticks picked before), as the grants name them. */
    fun heldReadWriteTrees(): Set<String>

    /** Gives up read access [takeRead] kept for [uri]; access the app held for something else is left alone. */
    fun release(uri: String)

    /** Gives up read access an earlier run of the app kept and never gave up (it was killed first). */
    fun releaseLeftovers()
}
