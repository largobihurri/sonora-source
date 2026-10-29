package io.sonora.core.data.library.tags

import java.util.logging.Level
import java.util.logging.Logger
import org.jaudiotagger.tag.TagOptionSingleton

/**
 * Process-wide JAudioTagger access. The library keeps global state (its option singleton and shared format readers),
 * so the scanner's tag reads and on-demand File Info reads run one at a time through [read].
 */
internal object JAudioTagger {
    /** Strong reference: java.util.logging keeps loggers weakly, which would drop the level. */
    private val logger: Logger = Logger.getLogger("org.jaudiotagger")
    private var configured = false

    /** Runs [block] holding the JAudioTagger lock, configured for Android first. */
    fun <T> read(block: () -> T): T = synchronized(this) {
        if (!configured) {
            // AndroidArtwork keeps picture handling to raw bytes (no java.awt / javax.imageio).
            TagOptionSingleton.getInstance().isAndroid = true
            logger.level = Level.OFF
            configured = true
        }
        block()
    }
}
