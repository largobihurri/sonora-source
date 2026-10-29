package io.sonora.core.watchsync

import java.security.MessageDigest

/**
 * The id a song has on the watch: stable for the same source file and encoding profile, so the phone can tell what is
 * already on the watch without encoding it, and safe to use as a file name there.
 */
object TrackIds {
    private const val LENGTH = 16
    private val VALID = Regex("[0-9a-f]{8,64}")

    /** 16 lowercase hex characters from a SHA-256 of [sourceUri] and [profileId]. */
    fun of(sourceUri: String, profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceUri.toByteArray(Charsets.UTF_8))
        digest.update(0) // so ("ab", "c") and ("a", "bc") differ
        digest.update(profileId.toByteArray(Charsets.UTF_8))
        return Sha256.toHex(digest.digest()).take(LENGTH)
    }

    /** Whether [id] is a form [of] could have made (8 to 64 lowercase hex characters): nothing a path could escape with. */
    fun isValid(id: String): Boolean = VALID.matches(id)
}
