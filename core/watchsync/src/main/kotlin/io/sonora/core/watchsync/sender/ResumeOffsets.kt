package io.sonora.core.watchsync.sender

/** The byte a transfer may restart from. */
object ResumeOffsets {
    /** The watch may ask for any byte from the start to the end of the file (the end meaning "all of it is here, verify"). */
    fun isValid(offset: Long, sizeBytes: Long): Boolean = offset in 0..sizeBytes
}
