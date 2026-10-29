package io.sonora.core.data.library.tags

import java.io.EOFException
import java.io.IOException

/** Tells a read that may succeed later apart from content that will never yield tags. */
internal object ReadFailures {
    /**
     * Revoked access and I/O errors are transient: retry next scan. JAudioTagger's own exceptions (bad header,
     * garbled tag), a truncated file ([EOFException]) and parser crashes are permanent: index the file untagged.
     */
    fun isTransient(failure: Throwable): Boolean =
        failure is SecurityException || (failure is IOException && failure !is EOFException)
}
