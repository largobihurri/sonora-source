package io.sonora.core.domain.library

import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import java.net.URLDecoder

/** A SAF tree as the overlap rule sees it: provider authority plus tree document id (e.g. `primary:Music/Rock`). */
data class TreeLocation(val authority: String, val documentId: String)

/** Which folders may join the library: folders never overlap, so no file is indexed (and listed) twice. */
object LibraryFolderRules {
    /** Where the folder picker opens: the shared Music folder on internal storage. */
    const val SUGGESTED_START_URI = "content://com.android.externalstorage.documents/document/primary%3AMusic"

    private val TREE_URI = Regex("content://([^/]+)/tree/([^/]+)(?:/.*)?")

    /** Parses `content://<authority>/tree/<encoded id>[/document/...]`; `null` when [treeUri] is not a tree URI. */
    fun location(treeUri: String): TreeLocation? {
        val (authority, encodedId) = TREE_URI.matchEntire(treeUri)?.destructured ?: return null
        // Uri.encode escapes '+' itself, so a literal '+' is part of the name, not an encoded space.
        val documentId = runCatching { URLDecoder.decode(encodedId.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()
        return documentId?.takeIf { it.isNotEmpty() }?.let { TreeLocation(authority, it) }
    }

    /**
     * True when [inner] is [outer] or lies inside it: same provider, and [outer]'s document id is a prefix of
     * [inner]'s at a `/` boundary, or [outer] is a volume root such as `primary:`. Providers with opaque ids
     * (no paths) only match exactly.
     */
    fun contains(outer: TreeLocation, inner: TreeLocation): Boolean {
        if (outer.authority != inner.authority) return false
        val parent = outer.documentId.trimEnd('/')
        val child = inner.documentId.trimEnd('/')
        return child == parent || child.startsWith("$parent/") || (parent.endsWith(':') && child.startsWith(parent))
    }

    /** Why [candidateTreeUri] cannot be added next to [existing], or `null` when it can. */
    fun conflict(candidateTreeUri: String, existing: List<LibraryFolder>): FolderConflict? {
        existing.firstOrNull { it.treeUri == candidateTreeUri }?.let { return FolderConflict.AlreadyAdded(it) }
        val candidate = location(candidateTreeUri) ?: return null
        for (folder in existing) {
            val other = location(folder.treeUri) ?: continue
            val inside = contains(other, candidate)
            val around = contains(candidate, other)
            when {
                inside && around -> return FolderConflict.AlreadyAdded(folder)
                inside -> return FolderConflict.InsideExisting(folder)
                around -> return FolderConflict.ContainsExisting(folder)
            }
        }
        return null
    }
}
