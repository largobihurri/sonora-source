package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.export.RemovableVolume

/** A system screen where a removable volume can be ejected, most specific first. */
enum class EjectScreen {
    /** The volume's own storage page (the root settings of the system's storage provider), when Settings has one. */
    VOLUME_SETTINGS,

    /** Settings' storage page, which lists the connected USB drives. */
    STORAGE_SETTINGS,

    /** Settings' memory card page. */
    MEMORY_CARD_SETTINGS,
}

/** Where "Eject stick" is. Apps cannot unmount a volume, so the user ejects it in Android's settings. */
sealed interface EjectState {
    data object Idle : EjectState

    /**
     * Sonora has let go of the stick; the screen opens the first of [screens] that exists. [target] is the volume to
     * eject, `null` when Sonora cannot tell which one it is.
     */
    data class OpenScreen(val target: RemovableVolume?, val screens: List<EjectScreen>) : EjectState

    /** Waiting for Android to let go of [target]; [screen] is where the user was sent, `null` when none would open. */
    data class Waiting(val target: RemovableVolume?, val screen: EjectScreen?) : EjectState

    /** Android no longer holds the stick ([name], when known): it can be pulled out. */
    data class SafeToRemove(val name: String?) : EjectState
}

/**
 * The eject steps as a pure state machine over the removable volumes Android reports. A volume is let go once Android
 * reports it unmounted, removed or no longer lists it; ejecting is not enough (it may still be writing).
 */
object StickEject {

    /**
     * Starts an eject of the chosen stick [chosenUuid], or, with none chosen, of the only volume in use. A chosen stick
     * that is not in use is already let go; it is never swapped for another volume (that may be an SD card).
     */
    fun request(chosenUuid: String?, volumes: List<RemovableVolume>): EjectState {
        val inUse = volumes.filter { it.isInUse }
        val target = if (chosenUuid != null) {
            inUse.firstOrNull { it.uuid.equals(chosenUuid, ignoreCase = true) }
                ?: return EjectState.SafeToRemove(volumes.firstOrNull { it.uuid.equals(chosenUuid, ignoreCase = true) }?.name)
        } else {
            when (inUse.size) {
                0 -> return EjectState.SafeToRemove(null)
                1 -> inUse.single()
                else -> null
            }
        }
        return EjectState.OpenScreen(target, screensFor(target))
    }

    /** The screens to try, in order: the volume's own page only when the volume is known. */
    fun screensFor(target: RemovableVolume?): List<EjectScreen> = buildList {
        if (target != null) add(EjectScreen.VOLUME_SETTINGS)
        add(EjectScreen.STORAGE_SETTINGS)
        add(EjectScreen.MEMORY_CARD_SETTINGS)
    }

    /** The screen opened [screen], or none would (`null`). */
    fun onScreenOpened(state: EjectState, screen: EjectScreen?): EjectState =
        if (state is EjectState.OpenScreen) EjectState.Waiting(state.target, screen) else state

    /** Android reported [volumes]: a target it let go of is safe to remove. */
    fun onVolumes(state: EjectState, volumes: List<RemovableVolume>): EjectState {
        val target = when (state) {
            is EjectState.OpenScreen -> state.target
            is EjectState.Waiting -> state.target
            EjectState.Idle, is EjectState.SafeToRemove -> return state
        }
        val held = if (target != null) {
            volumes.any { it.isInUse && it.uuid.equals(target.uuid, ignoreCase = true) }
        } else {
            volumes.any { it.isInUse }
        }
        return if (held) state else EjectState.SafeToRemove(target?.name)
    }
}
