package io.sonora.core.designsystem.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the system share sheet for audio files at [uris] (their [mimeTypes]; mixed or none share as `audio/`), headed
 * [chooserTitle]. A single song's [title] names it in the sheet's preview instead of the file's document id (MIUI's own
 * sheet ignores it). True once the sheet is open, or with nothing to share; false when no app can take the files.
 */
fun Context.shareAudio(uris: List<Uri>, mimeTypes: List<String>, title: String?, chooserTitle: String): Boolean {
    if (uris.isEmpty()) return true
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = mimeTypes.distinct().singleOrNull() ?: "audio/*"
    title?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
    // The clip carries the read grant to whichever app is chosen.
    send.clipData = ClipData.newRawUri(title, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
