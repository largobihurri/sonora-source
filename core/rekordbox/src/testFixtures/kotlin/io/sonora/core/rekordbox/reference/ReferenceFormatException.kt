package io.sonora.core.rekordbox.reference

/**
 * A file the reference readers cannot make sense of. [offset] is the byte position (from the start of the file) where
 * the problem was found; [truncated] is true when the data simply ends before what its own headers promise, so a
 * caller can tell a cut-off copy from a file of another format.
 */
sealed class ReferenceFormatException(message: String, val offset: Int, val truncated: Boolean) :
    RuntimeException(message)

/** The file is not a well-formed ANLZ analysis file (`.DAT`, `.EXT` or `.2EX`). */
class AnlzFormatException(message: String, offset: Int, truncated: Boolean = false) :
    ReferenceFormatException(message, offset, truncated)

/** The file is not a well-formed `export.pdb`. */
class PdbFormatException(message: String, offset: Int, truncated: Boolean = false) :
    ReferenceFormatException(message, offset, truncated)
