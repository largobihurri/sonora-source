package io.sonora.core.cast.server

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret first segment of every URL the cast server hands out: 128 random bits, new for every cast session. Only
 * the Cast receiver is told the URLs, so another device on the network cannot guess its way to the user's files.
 */
class CastToken(val value: String) {
    init {
        require(value.length == LENGTH) { "A cast token has $LENGTH characters" }
    }

    /** Whether [candidate] is this token, compared in constant time. */
    fun matches(candidate: String): Boolean =
        MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))

    override fun toString(): String = "CastToken(…)"

    companion object {
        private const val BYTES = 16
        const val LENGTH = BYTES * 2

        fun random(random: SecureRandom): CastToken {
            val bytes = ByteArray(BYTES).also(random::nextBytes)
            return CastToken(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
