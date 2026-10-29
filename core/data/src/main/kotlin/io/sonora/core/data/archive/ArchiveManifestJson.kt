package io.sonora.core.data.archive

import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ManifestError
import io.sonora.core.domain.archive.ManifestReading
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * `sonora.json` as JSON:
 *
 * ```
 * {"format":"sonora-archive","version":1,"kind":"album","name":"Record",
 *  "entries":[{"file":"01 - Band - Intro.flac","title":"Intro","artist":"Band","album":"Record",
 *              "albumArtist":"Band","trackNumber":1,"discNumber":1,"durationMs":61000}]}
 * ```
 *
 * Reading checks the format id and the version before anything else, so another program's file and a newer
 * Sonora's manifest are told apart from a broken one; unknown fields are ignored, so later versions 1.x can add some.
 */
object ArchiveManifestJson {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(manifest: ArchiveManifest): String = json.encodeToString(ManifestDto.serializer(), ManifestDto.of(manifest))

    fun decode(text: String): ManifestReading {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return malformed("not JSON (${e.message})")
        }
        val root = element as? JsonObject ?: return ManifestReading.Rejected(ManifestError.OtherFormat(null))

        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != ArchiveManifest.FORMAT_ID) return ManifestReading.Rejected(ManifestError.OtherFormat(format))
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return malformed("no numeric version")
        if (version > ArchiveManifest.VERSION) return ManifestReading.Rejected(ManifestError.NewerVersion(version))

        val dto = try {
            json.decodeFromJsonElement(ManifestDto.serializer(), root)
        } catch (e: SerializationException) {
            return malformed(e.message ?: "unreadable fields")
        } catch (e: IllegalArgumentException) {
            return malformed(e.message ?: "unreadable fields")
        }
        val kind = ArchiveKind.entries.firstOrNull { it.name.equals(dto.kind, ignoreCase = true) }
            ?: return malformed("unknown kind ${dto.kind}")
        val badFile = dto.entries.firstOrNull { !isPlainName(it.file) }
        if (badFile != null) return malformed("entry file is not a plain name: ${badFile.file}")
        return ManifestReading.Read(ArchiveManifest(kind, dto.name, dto.entries.map(EntryDto::toEntry)))
    }

    /** A file name of its own: not blank, not "." or "..", without a separator or a control character. */
    private fun isPlainName(file: String): Boolean =
        file.isNotBlank() && file != "." && file != ".." && file.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun malformed(reason: String) = ManifestReading.Rejected(ManifestError.Malformed(reason))

    @Serializable
    private data class ManifestDto(
        val format: String,
        val version: Int,
        val kind: String,
        val name: String,
        val entries: List<EntryDto>,
    ) {
        companion object {
            fun of(manifest: ArchiveManifest) = ManifestDto(
                format = ArchiveManifest.FORMAT_ID,
                version = ArchiveManifest.VERSION,
                kind = manifest.kind.name.lowercase(),
                name = manifest.name,
                entries = manifest.entries.map(EntryDto::of),
            )
        }
    }

    @Serializable
    private data class EntryDto(
        val file: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long = 0,
    ) {
        fun toEntry() = ArchiveEntry(file, title, artist, album, albumArtist, trackNumber, discNumber, durationMs)

        companion object {
            fun of(entry: ArchiveEntry) = EntryDto(
                entry.file, entry.title, entry.artist, entry.album, entry.albumArtist, entry.trackNumber,
                entry.discNumber, entry.durationMs,
            )
        }
    }
}
