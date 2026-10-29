package io.sonora.core.domain.tags

/** The tag fields the tag editor changes, in form order. [numeric] fields hold a whole number. */
enum class TagField(val numeric: Boolean = false) {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    GENRE,
    YEAR,
    TRACK_NUMBER(numeric = true),
    TRACK_TOTAL(numeric = true),
    DISC_NUMBER(numeric = true),
    DISC_TOTAL(numeric = true),
    BPM(numeric = true),
    KEY,
    COMMENT,
}

/** An embedded picture: its encoded bytes, MIME type (`image/jpeg`, `image/png`) and pixel size when known. */
class Picture(val bytes: ByteArray, val mimeType: String?, val width: Int? = null, val height: Int? = null) {
    override fun equals(other: Any?): Boolean =
        other is Picture && bytes.contentEquals(other.bytes) && mimeType == other.mimeType &&
            width == other.width && height == other.height

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (mimeType?.hashCode() ?: 0)

    override fun toString(): String = "Picture(${bytes.size} bytes, $mimeType, ${width}x$height)"
}

/** A file's editable tags as stored: the text of every tagged field (missing fields are absent) and its picture. */
data class SongTags(val fields: Map<TagField, String> = emptyMap(), val artwork: Picture? = null)

/** What happens to the embedded picture on save. */
sealed interface ArtworkChange {
    data object Keep : ArtworkChange

    data object Remove : ArtworkChange

    data class Replace(val picture: Picture) : ArtworkChange
}

/**
 * What to write into a file: the fields to set, where an empty value removes the field, and the picture change.
 * Fields left out keep what the file holds, so formats the editor does not understand (a "3/12" track, a full date)
 * survive a save that does not touch them.
 */
data class TagChanges(val fields: Map<TagField, String> = emptyMap(), val artwork: ArtworkChange = ArtworkChange.Keep) {
    val isEmpty: Boolean get() = fields.isEmpty() && artwork == ArtworkChange.Keep
}

/** Why an edited field cannot be saved. */
enum class TagError {
    /** Digits only: track and disc numbers, totals and BPM. */
    NOT_A_NUMBER,

    /** A number outside 1..[EditableTags.MAX_NUMBER]. */
    OUT_OF_RANGE,

    /** A track or disc number above its total. */
    ABOVE_TOTAL,

    /** A year (`2001`) or a date starting with one (`2001-05` or `2001-05-03`). */
    NOT_A_YEAR,
}

/**
 * The edit form's values: each field's text as the user typed it, and the picture change. Starts from the file's
 * [SongTags] ([of]); only the fields that differ from them are written ([changesFrom]) and checked ([errors]), so a
 * value the form cannot parse never blocks a save that leaves it alone.
 */
data class EditableTags(val fields: Map<TagField, String> = emptyMap(), val artwork: ArtworkChange = ArtworkChange.Keep) {

    operator fun get(field: TagField): String = fields[field].orEmpty()

    fun with(field: TagField, value: String): EditableTags = copy(fields = fields + (field to value))

    /** The picture the form shows: the file's own, none once removed, or the replacement. */
    fun shownArtwork(original: SongTags): Picture? = when (val change = artwork) {
        ArtworkChange.Keep -> original.artwork
        ArtworkChange.Remove -> null
        is ArtworkChange.Replace -> change.picture
    }

    /** The fields whose text differs from [original] once both are trimmed, with their trimmed value. */
    fun changesFrom(original: SongTags): TagChanges {
        val changed = TagField.entries.mapNotNull { field ->
            val value = this[field].trim()
            if (value == original.fields[field].orEmpty().trim()) null else field to value
        }.toMap()
        val picture = if (artwork == ArtworkChange.Remove && original.artwork == null) ArtworkChange.Keep else artwork
        return TagChanges(changed, picture)
    }

    /** Problems with the fields changed from [original]; empty when the form can be saved. */
    fun errors(original: SongTags): Map<TagField, TagError> {
        val changed = changesFrom(original).fields
        val errors = HashMap<TagField, TagError>()
        for ((field, value) in changed) {
            if (value.isEmpty()) continue
            val error = when {
                field == TagField.YEAR -> if (isYear(value)) null else TagError.NOT_A_YEAR
                !field.numeric -> null
                !value.all(Char::isDigit) -> TagError.NOT_A_NUMBER
                value.trimStart('0').length > 3 || value.toInt() !in 1..MAX_NUMBER -> TagError.OUT_OF_RANGE
                else -> null
            }
            error?.let { errors[field] = it }
        }
        checkTotal(TagField.TRACK_NUMBER, TagField.TRACK_TOTAL, changed, errors)
        checkTotal(TagField.DISC_NUMBER, TagField.DISC_TOTAL, changed, errors)
        return errors
    }

    /** A [number] above its [total], once either changed and both are valid numbers. */
    private fun checkTotal(number: TagField, total: TagField, changed: Map<TagField, String>, errors: MutableMap<TagField, TagError>) {
        if (number !in changed && total !in changed) return
        if (number in errors || total in errors) return
        val n = this[number].trim().toIntOrNull() ?: return
        val of = this[total].trim().toIntOrNull() ?: return
        if (n > of) errors[number] = TagError.ABOVE_TOTAL
    }

    companion object {
        /** Largest track, disc, total or BPM value the form accepts. */
        const val MAX_NUMBER = 999

        private val DATE = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$")

        /** The form for [tags], every field as stored. */
        fun of(tags: SongTags): EditableTags = EditableTags(tags.fields)

        private fun isYear(value: String): Boolean {
            val match = DATE.matchEntire(value) ?: return false
            val (year, month, day) = match.destructured
            return year.toInt() in 1000..9999 &&
                (month.isEmpty() || month.toInt() in 1..12) &&
                (day.isEmpty() || day.toInt() in 1..31)
        }
    }
}
