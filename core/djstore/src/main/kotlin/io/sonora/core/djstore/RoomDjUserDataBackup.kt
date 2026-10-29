package io.sonora.core.djstore

import android.util.Log
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djstore.db.CueDao
import io.sonora.core.djstore.db.GridEditDao
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The user's own marks of the DJ store (the grid edit and the cues of each track) in the app backup, next to the
 * analyses: one text record per track that has any, in the same list the analyses use. A record is [PREFIX] and the
 * base64 of a small JSON object (`v`, `id`, `grid`, `cues`), so it can never be mistaken for an SDJ1 analysis record
 * (base64 has no colon), and a build that does not know it skips it as an unreadable analysis record.
 *
 * ## Export
 *
 * One record per audio id that has a readable edit or readable cues, in audio id order. A row this build cannot read
 * (a later build's, or with an impossible value) is left out and stays in the store untouched.
 *
 * ## Restore
 *
 * A merge that never overwrites: an edit is stored only for a track that has none, and a cue only when the track has
 * no cue with that id (a hot cue pad that is already set keeps its cue). Fields it does not know are ignored, and a cue
 * with an impossible value is dropped without failing the record.
 */
class RoomDjUserDataBackup(private val gridEditDao: GridEditDao, private val cueDao: CueDao) {

    private val edits = RoomDjGridEditStore(gridEditDao)
    private val cues = RoomDjCueStore(cueDao)

    /** One record per track with a readable edit or cues. */
    suspend fun export(): List<String> {
        val ids = (gridEditDao.allAudioIds() + cueDao.allAudioIds()).toSortedSet()
        val records = ArrayList<String>()
        for (text in ids) {
            val id = AudioId.parse(text) ?: continue
            val edit = edits.get(id)
            val trackCues = cues.get(id)
            if (edit != null || trackCues.isNotEmpty()) records += encode(id, edit, trackCues)
        }
        return records
    }

    /** What restoring one record did: [Result.ADDED] when it added anything, else [Result.ALREADY_PRESENT]; `null` when it cannot be read. */
    suspend fun restore(text: String): Result? {
        val record = decode(text) ?: return null
        var added = false
        if (record.edit != null && edits.get(record.id) == null) {
            edits.put(record.id, record.edit)
            added = true
        }
        if (record.cues.isNotEmpty()) {
            val known = cues.get(record.id).mapTo(HashSet()) { it.id }
            for (cue in record.cues) {
                if (cue.id in known) continue
                cues.put(record.id, cue)
                added = true
            }
        }
        return if (added) Result.ADDED else Result.ALREADY_PRESENT
    }

    enum class Result { ADDED, ALREADY_PRESENT }

    private class Record(val id: AudioId, val edit: GridEdit?, val cues: List<DjCue>)

    private fun encode(id: AudioId, edit: GridEdit?, trackCues: List<DjCue>): String {
        val json = JSONObject().put("v", FORMAT).put("id", id.value)
        if (edit != null) json.put("grid", JSONObject().put("bpmHundredths", edit.bpmHundredths).put("anchorMs", edit.anchorMs))
        val array = JSONArray()
        for (cue in trackCues) {
            val item = JSONObject()
                .put("id", cue.id)
                .put("kind", if (cue.kind == CueKind.HOT) HOT else MEMORY)
                .put("positionMs", cue.positionMs)
                .put("colorIndex", cue.color.paletteIndex)
                .put("colorRgb", cue.color.rgb)
                .put("name", cue.name)
            cue.slot?.let { item.put("slot", it) }
            cue.loopEndMs?.let { item.put("loopEndMs", it) }
            array.put(item)
        }
        json.put("cues", array)
        return PREFIX + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decode(text: String): Record? {
        if (!text.startsWith(PREFIX)) return null
        return try {
            val json = JSONObject(String(Base64.getDecoder().decode(text.substring(PREFIX.length)), Charsets.UTF_8))
            val id = AudioId.parse(json.getString("id")) ?: return null
            val edit = json.optJSONObject("grid")?.let(::readEdit)
            val trackCues = ArrayList<DjCue>()
            val array = json.optJSONArray("cues")
            if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let(::readCue)?.let(trackCues::add)
            Record(id, edit, trackCues)
        } catch (_: IllegalArgumentException) { // not base64
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun readEdit(json: JSONObject): GridEdit? = try {
        GridEdit(json.getInt("bpmHundredths"), json.getDouble("anchorMs"))
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Restore: a grid edit with an impossible value was dropped", e)
        null
    } catch (_: JSONException) {
        null
    }

    private fun readCue(json: JSONObject): DjCue? = try {
        val kind = if (json.getString("kind") == HOT) CueKind.HOT else CueKind.MEMORY
        DjCue(
            id = json.getString("id"),
            kind = kind,
            slot = if (json.has("slot") && !json.isNull("slot")) json.getInt("slot") else null,
            positionMs = json.getDouble("positionMs"),
            loopEndMs = if (json.has("loopEndMs") && !json.isNull("loopEndMs")) json.getDouble("loopEndMs") else null,
            color = CueColor(json.getInt("colorIndex"), json.getInt("colorRgb")),
            name = json.optString("name", ""),
        )
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Restore: a cue with an impossible value was dropped", e)
        null
    } catch (_: JSONException) {
        null
    }

    companion object {
        /** What starts a user data record. */
        const val PREFIX: String = "sdju1:"

        private const val FORMAT = 1
        private const val HOT = "hot"
        private const val MEMORY = "memory"
        private const val TAG = "SonoraDjStore"
    }
}
