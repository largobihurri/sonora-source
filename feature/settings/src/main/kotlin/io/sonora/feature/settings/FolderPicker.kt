package io.sonora.feature.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import io.sonora.core.domain.library.LibraryFolderRules

/**
 * Launcher for the system folder picker (ACTION_OPEN_DOCUMENT_TREE), opening at the Music folder; [onPicked]
 * receives the chosen tree URI. The returned function returns false when no picker app exists.
 * (feature:library has the same launcher for its onboarding; features do not depend on each other.)
 */
@Composable
internal fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Boolean {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    return {
        try {
            launcher.launch(LibraryFolderRules.SUGGESTED_START_URI.toUri())
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
