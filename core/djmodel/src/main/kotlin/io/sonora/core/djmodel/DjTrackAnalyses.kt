package io.sonora.core.djmodel

import kotlinx.coroutines.flow.Flow

/**
 * The stored analysis of one track as the DJ screens need it, found by the track's uri (what the player knows) instead
 * of its [AudioId] (what the store is keyed by).
 *
 * It only reads: it never identifies (hashes) a file, so showing a track costs no second read while the analysis
 * scheduler hashes it. A track whose identity is not known yet simply has no analysis until it is.
 */
interface DjTrackAnalyses {

    /**
     * The analysis of the audio at [uri]: `null` while there is none (not identified yet, not analysed yet, or the file
     * cannot be looked up), then the analysis as soon as it exists, and again after every change to it. It follows the
     * analysis status of [uri], so a track identified or analysed after the call is picked up; cancel the collection
     * when the track is no longer shown.
     */
    fun analysisOf(uri: String): Flow<DjAnalysis?>
}
