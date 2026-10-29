package io.sonora.core.domain.export

import kotlinx.coroutines.flow.Flow

/**
 * A removable storage volume as Android reports it: a USB stick or an SD card (apps cannot tell them apart).
 */
data class RemovableVolume(
    /** The file system's id ("2C9C-2151" on FAT); a storage tree of its root is `…/tree/2C9C-2151%3A`. */
    val uuid: String,
    /** The name the system shows ("SONORA", "USB drive"). */
    val name: String,
    /** Its size, when the system says (only while it is mounted). */
    val totalBytes: Long?,
    val state: State,
) {
    enum class State { MOUNTED, MOUNTED_READ_ONLY, CHECKING, EJECTING, UNMOUNTED, REMOVED, BAD_REMOVAL, OTHER }

    /** Mounted for reading and writing: an export can go to it. */
    val isWritable: Boolean get() = state == State.MOUNTED

    /** Android is reading it, may write to it or is still letting it go: it must not be pulled out yet. */
    val isInUse: Boolean
        get() = state == State.MOUNTED || state == State.MOUNTED_READ_ONLY || state == State.CHECKING ||
            state == State.EJECTING
}

/** The removable volumes Android knows of. */
fun interface RemovableVolumes {
    /** The volumes now, then again each time one is mounted, ejected or removed. */
    fun volumes(): Flow<List<RemovableVolume>>
}
