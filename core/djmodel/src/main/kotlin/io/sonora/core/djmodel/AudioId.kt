package io.sonora.core.djmodel

/**
 * The identity of a track's audio, independent of the library row, the file name, its location and its tags:
 * `<scheme>:<sha256 hex>`, for example `ap1:3b4c...`.
 *
 * The scheme says what was hashed: [PAYLOAD_V1] the audio payload only (tag blocks excluded), [WHOLE_FILE_V1] the
 * whole file for containers the payload walker does not understand. A new hashing rule gets a new scheme, never a
 * silent change, so a stored id always means the same bytes. Any lower-case alphanumeric scheme starting with a
 * letter is accepted, so ids written by a later version can still be read.
 */
@JvmInline
value class AudioId private constructor(val value: String) {

    /** The part before the colon. */
    val scheme: String get() = value.substringBefore(SEPARATOR)

    /** The 64 lower-case hex digits of the SHA-256. */
    val hash: String get() = value.substringAfter(SEPARATOR)

    override fun toString(): String = value

    companion object {
        /** SHA-256 of the audio payload only, as the container walkers delimit it. */
        const val PAYLOAD_V1: String = "ap1"

        /** SHA-256 of the whole file: the fallback when the container cannot be walked. */
        const val WHOLE_FILE_V1: String = "wf1"

        private const val SEPARATOR = ':'
        private const val HASH_LENGTH = 64
        private val SCHEME = Regex("[a-z][a-z0-9]*")

        /** @throws IllegalArgumentException unless [scheme] is a lower-case tag and [sha256Hex] 64 lower-case hex digits. */
        fun of(scheme: String, sha256Hex: String): AudioId {
            require(SCHEME.matches(scheme)) { "Invalid audio id scheme: '$scheme'" }
            require(isHash(sha256Hex)) { "An audio id hash is $HASH_LENGTH lower-case hex digits: '$sha256Hex'" }
            return AudioId("$scheme$SEPARATOR$sha256Hex")
        }

        /** The id written as [value], or `null` when [text] is not one. */
        fun parse(text: String): AudioId? {
            val colon = text.indexOf(SEPARATOR)
            if (colon <= 0) return null
            val scheme = text.substring(0, colon)
            val hash = text.substring(colon + 1)
            return if (SCHEME.matches(scheme) && isHash(hash)) AudioId(text) else null
        }

        private fun isHash(text: String): Boolean =
            text.length == HASH_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
