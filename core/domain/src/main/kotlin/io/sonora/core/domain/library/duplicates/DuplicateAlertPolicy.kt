package io.sonora.core.domain.library.duplicates

/**
 * When the "duplicates piled up" notification is posted: once [THRESHOLD] groups or more wait, and again only when
 * more have piled up than the last notification told of. That count follows the groups down as they are cleared, so
 * a new pile after a clean-up is told of again.
 */
object DuplicateAlertPolicy {
    const val THRESHOLD = 20

    fun shouldNotify(groups: Int, lastNotifiedCount: Int, enabled: Boolean): Boolean =
        enabled && groups >= THRESHOLD && groups > lastNotifiedCount

    /** The count to remember after a check that found [groups] and [posted] (or not) a notification. */
    fun lastNotifiedAfter(groups: Int, lastNotifiedCount: Int, posted: Boolean): Int =
        if (posted) groups else minOf(lastNotifiedCount, groups)
}
