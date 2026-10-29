package io.sonora.core.domain.archive

/** A finished archive handed on: shared with another app, or saved where the user picks. */
interface ArchiveFiles {
    /** A `content://` URI another app can read [archive] through while a share grants it access. */
    fun shareUri(archive: CompressedArchive): String

    /**
     * Copies [archive] into the new document [documentUri] (from the system "create document" picker); false when
     * the archive is gone or the document cannot be written. Never throws.
     */
    suspend fun saveTo(archive: CompressedArchive, documentUri: String): Boolean
}
