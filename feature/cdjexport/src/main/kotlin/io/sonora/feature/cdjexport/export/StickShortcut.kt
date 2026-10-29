package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.export.RemovableVolume
import java.net.URLDecoder

/** How the export gets to a stick without walking through the system's file picker. */
internal sealed interface StickShortcut {
    /** No stick is mounted: the picker opens where it likes. */
    data object Picker : StickShortcut

    /** Sonora already holds access to [volume]'s root: [treeUri] (the grant as held) opens with no picker. */
    data class Open(val volume: RemovableVolume, val treeUri: String) : StickShortcut

    /** The picker opens at [volume]'s root, one tap from "Use this folder". */
    data class PickAt(val volume: RemovableVolume) : StickShortcut

    /** Several are mounted; a stick and an SD card look alike to apps, so the user chooses among [volumes]. */
    data class Choose(val volumes: List<RemovableVolume>) : StickShortcut
}

/**
 * Picks the [StickShortcut] from the removable volumes Android reports and the storage trees the app holds read and
 * write access to. Storage grants are per volume id, and Prepare USB keeps the id when it formats, so one grant serves
 * a stick for good.
 */
internal object StickShortcuts {

    private const val TREE_PREFIX = "content://com.android.externalstorage.documents/tree/"

    /** The shortcut when the user asks for "the stick": only volumes mounted for writing count. */
    fun choose(volumes: List<RemovableVolume>, grants: Collection<String>): StickShortcut {
        val writable = volumes.filter { it.isWritable }
        return when (writable.size) {
            0 -> StickShortcut.Picker
            1 -> forVolume(writable.single(), grants)
            else -> StickShortcut.Choose(writable)
        }
    }

    /** [volume] opened with the grant of its root when the app holds one, else the picker at its root. */
    fun forVolume(volume: RemovableVolume, grants: Collection<String>): StickShortcut {
        val grant = grants.firstOrNull { volumeOf(it).equals(volume.uuid, ignoreCase = true) }
        return if (grant != null) StickShortcut.Open(volume, grant) else StickShortcut.PickAt(volume)
    }

    /**
     * The volume id of [treeUri] when it is the root of a removable volume of the system's storage provider
     * (`…/tree/2C9C-2151%3A`); null for a subfolder, the phone's own storage or anything else.
     */
    fun volumeOf(treeUri: String): String? {
        if (!treeUri.startsWith(TREE_PREFIX)) return null
        val encoded = treeUri.removePrefix(TREE_PREFIX).substringBefore('/')
        val documentId = try {
            URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!documentId.endsWith(':') || documentId.count { it == ':' } != 1) return null
        val uuid = documentId.removeSuffix(":")
        return uuid.takeIf { it.isNotEmpty() && !it.equals("primary", ignoreCase = true) }
    }
}
