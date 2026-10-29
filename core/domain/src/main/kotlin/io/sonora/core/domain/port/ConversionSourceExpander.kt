package io.sonora.core.domain.port

import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.PickedSource

/** What the picks hold: the source files, and how many subfolders could not be read and were left out. */
data class ExpandedSources(val sources: List<BatchSource>, val skippedFolders: Int = 0)

/** Turns the user's picks into source files: folders recursively to their supported audio files, files as they are. */
fun interface ConversionSourceExpander {
    /**
     * Sources in pick order, each folder's files sorted by path ([io.sonora.core.domain.conversion.batch.ConversionInputs]);
     * a file picked twice (or inside two picked folders) appears once. A picked folder that cannot be listed fails;
     * a subfolder that cannot is left out with what it holds and counted in [ExpandedSources.skippedFolders].
     */
    suspend fun expand(picks: List<PickedSource>): ExpandedSources
}
