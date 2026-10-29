package io.sonora.core.domain.port

import kotlinx.coroutines.flow.Flow

/** The folder the user picked once for converted files; "Convertidos" is created inside it. */
data class OutputRootFolder(val treeUri: String, val displayName: String)

interface ConversionOutputRootStore {

    /** The chosen folder, or `null` before the user picks one. */
    val root: Flow<OutputRootFolder?>

    /**
     * Keeps [treeUri] (from the folder picker) as the output folder, persisting read and write access to it.
     *
     * @throws SecurityException when the picker did not grant persistable write access.
     */
    suspend fun set(treeUri: String): OutputRootFolder
}
