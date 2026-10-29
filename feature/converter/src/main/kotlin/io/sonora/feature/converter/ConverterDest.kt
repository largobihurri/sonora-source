package io.sonora.feature.converter

/** In-tab destinations of the Converter stack; the batch detail carries its id so the stack can be saved. */
internal sealed interface ConverterDest {
    data object Main : ConverterDest
    data object History : ConverterDest
    data class Batch(val batchId: Long) : ConverterDest

    /** "Add Music…": songs picked from the library. */
    data object LibraryPicker : ConverterDest

    /**
     * The output picker: for the batch-wide target ([source] `null`), for picked [source], or for song [child] inside
     * picked folder [source].
     */
    data class Output(val source: String?, val child: String?) : ConverterDest

    /** Picked folder [uri]'s format and its songs, each settable on its own. */
    data class Folder(val uri: String) : ConverterDest
}

private const val BATCH = "batch:"
private const val OUTPUT = "output:"
private const val FOLDER = "folder:"

/** Separates an [ConverterDest.Output]'s source from its child; never found in a content URI. */
private const val SEPARATOR = '\n'

/** Encodes the stack as strings for `rememberSaveable` (Bundle-friendly). */
internal fun encodeConverterStack(stack: List<ConverterDest>): List<String> = stack.map { dest ->
    when (dest) {
        ConverterDest.Main -> "main"
        ConverterDest.History -> "history"
        is ConverterDest.Batch -> "$BATCH${dest.batchId}"
        ConverterDest.LibraryPicker -> "library"
        is ConverterDest.Output -> OUTPUT + listOfNotNull(dest.source, dest.child).joinToString(SEPARATOR.toString())
        is ConverterDest.Folder -> "$FOLDER${dest.uri}"
    }
}

/** Restores an encoded stack, dropping unreadable entries; the result always starts at [ConverterDest.Main]. */
internal fun decodeConverterStack(encoded: List<String>): List<ConverterDest> {
    val decoded = encoded.mapNotNull { value ->
        when {
            value == "history" -> ConverterDest.History
            value == "library" -> ConverterDest.LibraryPicker
            value.startsWith(BATCH) -> value.removePrefix(BATCH).toLongOrNull()?.let(ConverterDest::Batch)
            value.startsWith(OUTPUT) -> value.removePrefix(OUTPUT).takeIf { it.isNotEmpty() }?.split(SEPARATOR).let { parts ->
                ConverterDest.Output(parts?.getOrNull(0), parts?.getOrNull(1))
            }
            value.startsWith(FOLDER) -> value.removePrefix(FOLDER).takeIf { it.isNotEmpty() }?.let(ConverterDest::Folder)
            else -> null
        }
    }
    return listOf(ConverterDest.Main) + decoded
}

/** Shows batch [batchId] (from a notification) on the converter itself; false when there is none to open. */
internal fun openBatchFromNotification(batchId: Long?, stack: MutableList<ConverterDest>, show: (Long) -> Unit): Boolean {
    val id = batchId ?: return false
    show(id)
    backToMain(stack)
    return true
}

/** Songs sent from the Library ([requested]) show on the converter's main screen; false when nothing was sent. */
internal fun showSourcesFromLibrary(requested: Boolean, stack: MutableList<ConverterDest>): Boolean {
    if (!requested) return false
    backToMain(stack)
    return true
}

private fun backToMain(stack: MutableList<ConverterDest>) {
    if (stack.size > 1) stack.subList(1, stack.size).clear()
}
