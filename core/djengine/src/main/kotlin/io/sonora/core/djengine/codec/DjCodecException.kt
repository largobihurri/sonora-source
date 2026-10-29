package io.sonora.core.djengine.codec

/**
 * Why a DJ record could not be decoded. A decoder either returns the exact value that was encoded or throws one of
 * these; it never returns a partly read or guessed value.
 */
sealed class DjCodecException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The bytes do not start with the `SDJ1` magic: they are not a DJ record at all. */
    class BadMagic(message: String) : DjCodecException(message)

    /**
     * The record was written by a newer format ([version]) than this build reads. The bytes are not damaged: the
     * caller keeps them untouched so a newer build can read them later.
     */
    class UnsupportedVersion(val version: Int) :
        DjCodecException("Unsupported DJ record format version $version (this build reads up to ${DjAnalysisCodec.FORMAT_VERSION})")

    /** The record is damaged or inconsistent: truncated, checksum or length mismatch, wrong kind, invalid field. */
    class Corrupt(message: String, cause: Throwable? = null) : DjCodecException(message, cause)
}
