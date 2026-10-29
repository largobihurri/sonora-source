package io.sonora.core.djanalysis

import io.sonora.core.djengine.DJ_ANALYZER_VERSION
import io.sonora.core.djmodel.DjAnalyzerInfo

/** The analyser of this build: [DJ_ANALYZER_VERSION], which the DJ screen compares with the version of a stored analysis. */
object CurrentDjAnalyzer : DjAnalyzerInfo {
    override val version: Int = DJ_ANALYZER_VERSION
}
