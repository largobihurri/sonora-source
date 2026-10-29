package io.sonora.core.domain.log

import java.security.MessageDigest

/**
 * The form of a URI, path or file name that may go into a log line: the user's folder and file names never do.
 *
 * A URI keeps its scheme and authority (`content://com.android.externalstorage.documents/…#1a2b3c4d`), which tells
 * which provider was involved; everything after the authority (path, query, fragment) becomes `…` plus the first
 * 8 hex digits of the SHA-256 of the whole value, so lines about the same document still match up. Anything else
 * (a path, a file or folder name) becomes only `…#<hash>`. A URI with nothing after its authority, an empty value
 * and `null` carry nothing personal and are returned as they are. Objects (such as `android.net.Uri`) are redacted
 * through their string form.
 */
fun redactForLog(value: Any?): String {
    if (value == null) return "null"
    val text = value.toString()
    if (text.isEmpty()) return text
    val uri = URI_PREFIX.find(text)
    if (uri != null) {
        val prefix = uri.value
        return if (prefix.length == text.length) text else "$prefix/…#${shortHash(text)}"
    }
    return "…#${shortHash(text)}"
}

/** `scheme://authority`, the authority ending at the first `/`, `?` or `#`. */
private val URI_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*")

private const val HASH_HEX_DIGITS = 8

private fun shortHash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(HASH_HEX_DIGITS)
