package io.sonora.core.data.backup

import io.sonora.core.domain.backup.BackupError
import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlaylist
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.BackupReading
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.LibraryBackup
import io.sonora.core.domain.backup.SettingValue
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.port.BackupCodec
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The backup file as JSON:
 *
 * ```
 * {"format":"sonora-backup","version":2,"createdAtMs":1759536000000,
 *  "songs":[{"key":"content://…","title":"Halo","artist":"Beyoncé","album":"I Am…","durationMs":261000}],
 *  "playlists":[{"id":3,"name":"Mix","description":"","entries":["content://…"]}],
 *  "favourites":[{"kind":"SONG","key":"content://…","addedAtMs":1}],
 *  "pins":[{"context":"songs","kind":"SONG","key":"content://…","pinnedAtMs":2}],
 *  "hidden":[{"kind":"ALBUM","key":"42","title":"Record","subtitle":"Band","hiddenAtMs":3}],
 *  "plays":[{"key":"content://…","count":7,"lastPlayedMs":4}],
 *  "settings":[{"name":"sonora_settings","values":[{"key":"appearance.theme","type":"string","value":"DARK"}]}],
 *  "djAnalyses":["U0RKMQAB…"]}
 * ```
 *
 * Version 2 added `djAnalyses`: the DJ store's analyses, one base64 string each, opaque here (a version 1 file has
 * none and reads as having an empty list). An entry that is not a JSON string reads as the empty string, which the DJ
 * store counts as an unreadable record, so one bad entry never fails the file. About 220 characters a track: 20,000
 * tracks are about 4.5 MB, far under [SafBackupDocuments.MAX_BYTES].
 *
 * Reading checks the format id and the version before anything else, so another program's file and a newer Sonora's
 * backup are told apart from a broken one. Unknown fields, mark kinds and setting types are skipped, so later 1.x
 * versions can add some; a missing section reads as empty.
 */
class BackupJson @Inject constructor() : BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun encode(backup: LibraryBackup): String = json.encodeToString(BackupDto.serializer(), BackupDto.of(backup))

    override fun decode(text: String): BackupReading {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return malformed("not JSON (${e.message})")
        }
        val root = element as? JsonObject ?: return BackupReading.Rejected(BackupError.OtherFormat(null))

        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != LibraryBackup.FORMAT_ID) return BackupReading.Rejected(BackupError.OtherFormat(format))
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return malformed("no numeric version")
        if (version < 1) return malformed("version $version")
        if (version > LibraryBackup.VERSION) return BackupReading.Rejected(BackupError.NewerVersion(version))

        val dto = try {
            json.decodeFromJsonElement(BackupDto.serializer(), root)
        } catch (e: SerializationException) {
            return malformed(e.message ?: "unreadable fields")
        } catch (e: IllegalArgumentException) {
            return malformed(e.message ?: "unreadable fields")
        }
        return BackupReading.Read(dto.toBackup())
    }

    private fun malformed(reason: String) = BackupReading.Rejected(BackupError.Malformed(reason))

    @Serializable
    private data class BackupDto(
        val format: String,
        val version: Int,
        val createdAtMs: Long = 0,
        val songs: List<SongDto> = emptyList(),
        val playlists: List<PlaylistDto> = emptyList(),
        val favourites: List<FavouriteDto> = emptyList(),
        val pins: List<PinDto> = emptyList(),
        val hidden: List<HiddenDto> = emptyList(),
        val plays: List<PlaysDto> = emptyList(),
        val settings: List<SettingsFileDto> = emptyList(),
        val djAnalyses: List<JsonElement> = emptyList(),
    ) {
        fun toBackup() = LibraryBackup(
            createdAtMs = createdAtMs,
            songs = songs.map { SongIdentity(it.key, it.title, it.artist, it.album, it.durationMs) }.distinctBy { it.key },
            playlists = playlists.map { BackupPlaylist(it.id, it.name, it.description, it.entries) },
            favourites = favourites.mapNotNull { dto -> ref(dto.kind, dto.key)?.let { BackupFavourite(it, dto.addedAtMs) } },
            pins = pins.mapNotNull { dto -> ref(dto.kind, dto.key)?.let { BackupPin(PinContext(dto.context), it, dto.pinnedAtMs) } },
            hidden = hidden.mapNotNull { dto -> ref(dto.kind, dto.key)?.let { HiddenItem(it, dto.title, dto.subtitle, dto.hiddenAtMs) } },
            plays = plays.filter { it.count > 0 }.map { BackupPlays(it.key, it.count, it.lastPlayedMs) },
            settings = settings.map { file -> BackupSettingsFile(file.name, file.values.mapNotNull(SettingDto::toEntry).toMap()) },
            djAnalyses = djAnalyses.map { entry -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() },
        )

        companion object {
            fun of(backup: LibraryBackup) = BackupDto(
                format = LibraryBackup.FORMAT_ID,
                version = LibraryBackup.VERSION,
                createdAtMs = backup.createdAtMs,
                songs = backup.songs.map { SongDto(it.key, it.title, it.artist, it.album, it.durationMs) },
                playlists = backup.playlists.map { PlaylistDto(it.id, it.name, it.description, it.entries) },
                favourites = backup.favourites.map { FavouriteDto(it.ref.kind.name, it.ref.key, it.addedAtMs) },
                pins = backup.pins.map { PinDto(it.context.id, it.ref.kind.name, it.ref.key, it.pinnedAtMs) },
                hidden = backup.hidden.map { HiddenDto(it.ref.kind.name, it.ref.key, it.title, it.subtitle, it.hiddenAtMs) },
                plays = backup.plays.map { PlaysDto(it.key, it.count, it.lastPlayedMs) },
                settings = backup.settings.map { file ->
                    SettingsFileDto(file.name, file.values.map { (key, value) -> SettingDto.of(key, value) })
                },
                djAnalyses = backup.djAnalyses.map(::JsonPrimitive),
            )

            /** `null` for a kind this version does not know (written by a newer one). */
            private fun ref(kind: String, key: String): ItemRef? =
                MarkKind.entries.firstOrNull { it.name == kind }?.let { ItemRef(it, key) }
        }
    }

    @Serializable
    private data class SongDto(
        val key: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0,
    )

    @Serializable
    private data class PlaylistDto(val id: Long, val name: String, val description: String = "", val entries: List<String> = emptyList())

    @Serializable
    private data class FavouriteDto(val kind: String, val key: String, val addedAtMs: Long = 0)

    @Serializable
    private data class PinDto(val context: String, val kind: String, val key: String, val pinnedAtMs: Long = 0)

    @Serializable
    private data class HiddenDto(val kind: String, val key: String, val title: String = "", val subtitle: String = "", val hiddenAtMs: Long = 0)

    @Serializable
    private data class PlaysDto(val key: String, val count: Int, val lastPlayedMs: Long = 0)

    @Serializable
    private data class SettingsFileDto(val name: String, val values: List<SettingDto> = emptyList())

    /** One typed value; [type] names how [value] is to be read. */
    @Serializable
    private data class SettingDto(val key: String, val type: String, val value: JsonElement = JsonNull) {

        /** The value as [type] says; `null` for an unknown type or a value of another type. */
        fun toEntry(): Pair<String, SettingValue>? {
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeIf { !it.isString && it !is JsonNull }
            val setting = when (type) {
                BOOLEAN -> number?.booleanOrNull?.let(SettingValue::BooleanValue)
                INT -> number?.intOrNull?.let(SettingValue::IntValue)
                LONG -> number?.longOrNull?.let(SettingValue::LongValue)
                FLOAT -> number?.floatOrNull?.let(SettingValue::FloatValue)
                DOUBLE -> number?.doubleOrNull?.let(SettingValue::DoubleValue)
                STRING -> primitive?.takeIf { it.isString }?.content?.let(SettingValue::StringValue)
                STRING_SET -> (value as? JsonArray)
                    ?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: return null }
                    ?.toSet()
                    ?.let(SettingValue::StringSetValue)
                else -> null
            }
            return setting?.let { key to it }
        }

        companion object {
            const val BOOLEAN = "boolean"
            const val INT = "int"
            const val LONG = "long"
            const val FLOAT = "float"
            const val DOUBLE = "double"
            const val STRING = "string"
            const val STRING_SET = "stringSet"

            fun of(key: String, value: SettingValue): SettingDto = when (value) {
                is SettingValue.BooleanValue -> SettingDto(key, BOOLEAN, JsonPrimitive(value.value))
                is SettingValue.IntValue -> SettingDto(key, INT, JsonPrimitive(value.value))
                is SettingValue.LongValue -> SettingDto(key, LONG, JsonPrimitive(value.value))
                is SettingValue.FloatValue -> SettingDto(key, FLOAT, JsonPrimitive(value.value))
                is SettingValue.DoubleValue -> SettingDto(key, DOUBLE, JsonPrimitive(value.value))
                is SettingValue.StringValue -> SettingDto(key, STRING, JsonPrimitive(value.value))
                is SettingValue.StringSetValue -> SettingDto(key, STRING_SET, JsonArray(value.value.sorted().map(::JsonPrimitive)))
            }
        }
    }
}
