package io.sonora.feature.cdjexport.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log

/**
 * Opens the first of [screens] that exists on this phone (checked with `resolveActivity`, then started; a screen that
 * still refuses to start is skipped) and returns it, or null when none would open. [context] should be the activity,
 * so Back returns to Sonora.
 */
fun openEjectScreen(context: Context, screens: List<EjectScreen>, volumeUuid: String?): EjectScreen? =
    screens.firstOrNull { screen ->
        val intent = intentFor(screen, volumeUuid) ?: return@firstOrNull false
        if (intent.resolveActivity(context.packageManager) == null) return@firstOrNull false
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No screen for $screen", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "The screen for $screen refused to open", e)
            false
        }
    }

private fun intentFor(screen: EjectScreen, volumeUuid: String?): Intent? = when (screen) {
    // What the system file picker opens for a volume's settings: Settings' page of that volume, with its Eject button.
    EjectScreen.VOLUME_SETTINGS -> volumeUuid?.let {
        Intent(ACTION_DOCUMENT_ROOT_SETTINGS)
            .setDataAndType(DocumentsContract.buildRootUri(EXTERNAL_STORAGE, it), DocumentsContract.Root.MIME_TYPE_ITEM)
    }
    EjectScreen.STORAGE_SETTINGS -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    EjectScreen.MEMORY_CARD_SETTINGS -> Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
}

/** `DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS`, hidden in the SDK; Settings handles it for storage volumes. */
private const val ACTION_DOCUMENT_ROOT_SETTINGS = "android.provider.action.DOCUMENT_ROOT_SETTINGS"

private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

private const val TAG = "SonoraExport"
