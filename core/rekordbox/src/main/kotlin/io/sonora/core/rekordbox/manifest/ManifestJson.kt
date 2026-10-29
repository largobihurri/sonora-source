package io.sonora.core.rekordbox.manifest

import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.pdb.DeviceFileType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The manifest as UTF-8 JSON (schema [VERSION]):
 * ```
 * {"format":"sonora-stick-library","version":1,
 *  "tracks":[{"id":1,"title":"…","artist":"…"|null,"album":…,"genre":…,"trackNumber":…,"year":…,
 *             "filePath":"/Contents/…","analyzePath":"/PIONEER/USBANLZ/…/ANLZ0000.DAT","fileSize":…,
 *             "sha256":"…","sampleRate":44100,"bitDepth":16,"channels":2,"durationSeconds":…,"bpm":128.0,
 *             "key":"8A"|null,"dateAdded":"2026-10-03","fileType":"AIFF","analyzer":"…",
 *             "comment":"…"|null,"artworkId":1|null}],
 *  "playlists":[{"name":"…","trackIds":[1,2],"created":"2026-10-03"|null}],
 *  "artwork":[{"id":1,"sha256":"…"}]}
 * ```
 * Keys are stored by their alphanumeric name. Unknown members are ignored, so a later version may add some; a higher
 * `version` means an incompatible change and is refused. Track ids, file paths and artwork ids must be unique.
 *
 * `comment`, `artworkId` and `artwork` came later within version 1: a track without an `artworkId` member was listed by
 * an older Sonora and reads as [ManifestTrack.extrasRead] `false` (written back the same way until an export fills it
 * in); a manifest without `artwork` has no pictures.
 *
 * Decoding is strict: any document this version cannot read in full (bad JSON, nesting deeper than [MAX_DEPTH], a
 * missing or mistyped member, a whole number with a fraction or out of its range, a file size beyond 32 bits) is a
 * [ManifestFormatException], never a partial manifest or another exception.
 *
 * The manifest is small and flat, so a minimal JSON writer and reader live here instead of a library dependency.
 */
object ManifestJson {
    const val FORMAT = "sonora-stick-library"
    const val VERSION = 1

    /** Deepest nesting of arrays and objects a manifest may have; the schema itself needs 3. */
    const val MAX_DEPTH = 64

    private const val MAX_FILE_SIZE = 0xFFFF_FFFFL

    fun encode(manifest: StickManifest): ByteArray = buildString {
        append("{\"format\":").string(FORMAT).append(",\"version\":").append(VERSION)
        append(",\"tracks\":[")
        manifest.tracks.forEachIndexed { i, track ->
            if (i > 0) append(',')
            append('\n')
            track(track)
        }
        append("],\"playlists\":[")
        manifest.playlists.forEachIndexed { i, playlist ->
            if (i > 0) append(',')
            append("\n{\"name\":").string(playlist.name)
            append(",\"trackIds\":[").append(playlist.trackIds.joinToString(",")).append(']')
            append(",\"created\":").string(playlist.created?.toString())
            append('}')
        }
        append("],\"artwork\":[")
        manifest.artwork.forEachIndexed { i, picture ->
            if (i > 0) append(',')
            append("\n{\"id\":").append(picture.id)
            append(",\"sha256\":").string(picture.sha256)
            append('}')
        }
        append("]}\n")
    }.toByteArray(Charsets.UTF_8)

    /** @throws ManifestFormatException when [bytes] are not a manifest this version can read. */
    fun decode(bytes: ByteArray): StickManifest {
        val root = try {
            JsonReader(utf8(bytes)).document()
        } catch (e: IllegalArgumentException) {
            throw ManifestFormatException.Malformed(e.message ?: "invalid JSON", e)
        } as? Map<*, *> ?: throw ManifestFormatException.Malformed("not a JSON object")
        if (root["format"] != FORMAT) throw ManifestFormatException.Malformed("not a Sonora stick library")
        try {
            if (root["version"] !is Number) fail("no version")
            val version = root.int("version")
            if (version > VERSION) throw ManifestFormatException.NewerVersion(version)
            val tracks = root.list("tracks").map { readTrack(it as? Map<*, *> ?: fail("track is not an object")) }
            val playlists = root.list("playlists").map { readPlaylist(it as? Map<*, *> ?: fail("playlist is not an object")) }
            val artwork = root.list("artwork").map { readArtwork(it as? Map<*, *> ?: fail("artwork is not an object")) }
            require(tracks.map { it.id }.toSet().size == tracks.size) { "repeated track id" }
            require(tracks.map { it.filePath.uppercase() }.toSet().size == tracks.size) { "repeated file path" }
            require(artwork.map { it.id }.toSet().size == artwork.size) { "repeated artwork id" }
            return StickManifest(tracks, playlists, artwork)
        } catch (e: ManifestFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ManifestFormatException.Malformed(e.message ?: "invalid member", e)
        } catch (e: DateTimeParseException) {
            throw ManifestFormatException.Malformed("invalid date", e)
        }
    }

    private fun StringBuilder.track(track: ManifestTrack) {
        append("{\"id\":").append(track.id)
        append(",\"title\":").string(track.title)
        append(",\"artist\":").string(track.artist)
        append(",\"album\":").string(track.album)
        append(",\"genre\":").string(track.genre)
        append(",\"trackNumber\":").append(track.trackNumber)
        append(",\"year\":").append(track.year)
        append(",\"filePath\":").string(track.filePath)
        append(",\"analyzePath\":").string(track.analyzePath)
        append(",\"fileSize\":").append(track.fileSize)
        append(",\"sha256\":").string(track.contentSha256)
        append(",\"sampleRate\":").append(track.sampleRate)
        append(",\"bitDepth\":").append(track.bitDepth)
        append(",\"channels\":").append(track.channelCount)
        append(",\"durationSeconds\":").append(track.durationSeconds)
        // JSON has no Infinity or NaN: such a tempo is stored as 0, which the library treats as unknown anyway.
        append(",\"bpm\":").append(track.bpm.takeIf { it.isFinite() } ?: 0.0)
        append(",\"key\":").string(track.key?.alphanumeric)
        append(",\"dateAdded\":").string(track.dateAdded.toString())
        append(",\"fileType\":").string(track.fileType.name)
        append(",\"analyzer\":").string(track.analyzerVersion)
        // A track whose extras were never read keeps saying so: no artworkId member, as an older Sonora wrote it.
        if (track.extrasRead) {
            append(",\"comment\":").string(track.comment)
            append(",\"artworkId\":").append(track.artworkId)
        }
        append('}')
    }

    private fun readTrack(json: Map<*, *>) = ManifestTrack(
        id = json.int("id").also { require(it > 0) { "track id $it" } },
        title = json.string("title"),
        artist = json.optionalString("artist"),
        album = json.optionalString("album"),
        genre = json.optionalString("genre"),
        trackNumber = json.optionalInt("trackNumber"),
        year = json.optionalInt("year"),
        filePath = json.string("filePath").also { require(it.startsWith("/")) { "file path $it" } },
        analyzePath = json.string("analyzePath").also { require(it.startsWith("/")) { "analyze path $it" } },
        fileSize = json.long("fileSize").also { require(it in 0..MAX_FILE_SIZE) { "file size $it" } },
        contentSha256 = json.string("sha256"),
        sampleRate = json.int("sampleRate"),
        bitDepth = json.int("bitDepth"),
        channelCount = json.int("channels"),
        durationSeconds = json.int("durationSeconds"),
        bpm = json.number("bpm").toDouble(),
        key = json.optionalString("key")?.let { MusicalKey.fromAlphanumeric(it) ?: fail("unknown key $it") },
        dateAdded = LocalDate.parse(json.string("dateAdded")),
        fileType = json.optionalString("fileType")?.let { name ->
            DeviceFileType.entries.firstOrNull { it.name == name } ?: fail("unknown file type $name")
        } ?: DeviceFileType.AIFF,
        analyzerVersion = json.optionalString("analyzer").orEmpty(),
        comment = json.optionalString("comment"),
        artworkId = json.optionalInt("artworkId")?.also { require(it > 0) { "artwork id $it" } },
        extrasRead = json.containsKey("artworkId"),
    )

    private fun readArtwork(json: Map<*, *>) = ManifestArtwork(
        id = json.int("id").also { require(it > 0) { "artwork id $it" } },
        sha256 = json.string("sha256"),
    )

    private fun readPlaylist(json: Map<*, *>) = ManifestPlaylist(
        name = json.string("name"),
        trackIds = json.list("trackIds").map { checkedInt(it as? Number ?: fail("track id $it"), "track id") },
        created = json.optionalString("created")?.let(LocalDate::parse),
    )

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw ManifestFormatException.Malformed("not UTF-8", e)
    }

    private fun fail(detail: String): Nothing = throw ManifestFormatException.Malformed(detail)

    private fun Map<*, *>.list(name: String): List<*> = when (val value = this[name]) {
        null -> if (containsKey(name)) fail("$name is null") else emptyList<Any>()
        is List<*> -> value
        else -> fail("$name is not a list")
    }

    private fun Map<*, *>.string(name: String): String = this[name] as? String ?: fail("missing $name")

    private fun Map<*, *>.optionalString(name: String): String? = when (val value = this[name]) {
        null, is String -> value as String?
        else -> fail("$name is not a string")
    }

    private fun Map<*, *>.number(name: String): Number = this[name] as? Number ?: fail("missing $name")

    private fun Map<*, *>.optionalNumber(name: String): Number? = when (val value = this[name]) {
        null, is Number -> value as Number?
        else -> fail("$name is not a number")
    }

    private fun Map<*, *>.int(name: String): Int = checkedInt(number(name), name)

    private fun Map<*, *>.optionalInt(name: String): Int? = optionalNumber(name)?.let { checkedInt(it, name) }

    private fun Map<*, *>.long(name: String): Long = checkedLong(number(name), name)

    /** [value] as an [Int]; @throws IllegalArgumentException when it has a fraction or does not fit. */
    private fun checkedInt(value: Number, name: String): Int {
        val whole = checkedLong(value, name)
        require(whole in Int.MIN_VALUE..Int.MAX_VALUE) { "$name is $value" }
        return whole.toInt()
    }

    /** [value] as a [Long]; @throws IllegalArgumentException when it has a fraction or does not fit. */
    private fun checkedLong(value: Number, name: String): Long {
        // The reader gives Long for every integer that fits one; a Double is whole only if it is exactly a Long too.
        if (value is Long) return value
        val double = value.toDouble()
        require(double % 1.0 == 0.0 && double >= Long.MIN_VALUE.toDouble() && double < Long.MAX_VALUE.toDouble()) {
            "$name is $value"
        }
        return double.toLong()
    }

    private fun StringBuilder.string(text: String?): StringBuilder {
        if (text == null) return append("null")
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        return append('"')
    }
}

/**
 * A strict reader for one JSON document: objects become [Map]s (in member order), arrays [List]s, numbers [Long] when
 * integral and [Double] otherwise. Every syntax error, and nesting deeper than [maxDepth], is an
 * [IllegalArgumentException] (the reader recurses, so the depth bound also bounds its stack).
 */
private class JsonReader(private val text: String, private val maxDepth: Int = ManifestJson.MAX_DEPTH) {
    private var at = 0
    private var depth = 0

    fun document(): Any? {
        val value = value()
        skipSpace()
        require(at == text.length) { "trailing data at $at" }
        return value
    }

    private fun value(): Any? {
        skipSpace()
        require(at < text.length) { "unexpected end" }
        return when (text[at]) {
            '{' -> nested(::obj)
            '[' -> nested(::array)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun <T> nested(read: () -> T): T {
        require(++depth <= maxDepth) { "nested deeper than $maxDepth at $at" }
        return read().also { depth-- }
    }

    private fun obj(): Map<String, Any?> {
        val members = LinkedHashMap<String, Any?>()
        at++
        skipSpace()
        if (peek() == '}') return members.also { at++ }
        while (true) {
            skipSpace()
            require(peek() == '"') { "expected a member name at $at" }
            val name = string()
            skipSpace()
            expect(':')
            members[name] = value()
            skipSpace()
            if (peek() == ',') at++ else break
        }
        expect('}')
        return members
    }

    private fun array(): List<Any?> {
        val items = mutableListOf<Any?>()
        at++
        skipSpace()
        if (peek() == ']') return items.also { at++ }
        while (true) {
            items += value()
            skipSpace()
            if (peek() == ',') at++ else break
        }
        expect(']')
        return items
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            require(at < text.length) { "unterminated string" }
            when (val c = text[at++]) {
                '"' -> return out.toString()
                '\\' -> {
                    require(at < text.length) { "unterminated escape" }
                    when (val e = text[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= text.length) { "short \\u escape" }
                            out.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e")
                    }
                }
                else -> {
                    require(c >= ' ') { "control character in string" }
                    out.append(c)
                }
            }
        }
    }

    private fun number(): Number {
        val start = at
        while (at < text.length && text[at] in NUMBER_CHARS) at++
        val token = text.substring(start, at)
        require(NUMBER.matches(token)) { "bad value at $start" }
        return token.toLongOrNull() ?: token.toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, at)) { "bad literal at $at" }
        at += word.length
        return value
    }

    private fun expect(c: Char) {
        require(peek() == c) { "expected '$c' at $at" }
        at++
    }

    private fun peek(): Char? = text.getOrNull(at)

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private companion object {
        const val NUMBER_CHARS = "+-0123456789.eE"
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
