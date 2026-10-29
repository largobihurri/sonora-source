# Sonora external control API

Other apps, and AI agents running on a PC over adb, can control Sonora and read its library through one exported
`ContentProvider`. This page is the contract: what is allowed, every method and query, what comes back, and how the
API is versioned. API version: **1**.

- Authority: `<applicationId>.api`, so `content://io.sonora.player.api` for the everyday build (a nightly build
  installed next to it is `io.sonora.player.nightly.api`).
- `query()` reads (state, queue, library lists) and returns a `Cursor`.
- `call(method, arg, extras)` runs a command and returns a `Bundle`.
- The API takes library ids and plain text only. It never accepts a URI or a file path, and it never returns one.

## Who may use it

Two things have to be true for a caller that is not Sonora itself:

1. **The switch is on.** Settings > External control is off by default. While it is off, every external caller is
   denied, the adb shell included.
2. **The caller is allowed.** Decided on the calling uid, never on a package name:

| Caller | Switch off | Switch on |
|---|---|---|
| Sonora itself | allowed | allowed |
| `adb shell` (uid 2000) and root (uid 0) | denied | allowed |
| Any other app | denied | allowed only if it holds `io.sonora.permission.EXTERNAL_CONTROL` |

A denied `query()` or `call()` throws a `SecurityException` and nothing runs. Its message says which rule failed
(`External control is off...` or `The caller needs the io.sonora.permission.EXTERNAL_CONTROL permission.`).

### The permission

`io.sonora.permission.EXTERNAL_CONTROL` has protection level `dangerous`: the user grants it per app. An app that
wants to use the API:

1. Declares it in its manifest:

   ```xml
   <uses-permission android:name="io.sonora.permission.EXTERNAL_CONTROL" />
   ```

2. Asks for it at runtime (`ActivityCompat.requestPermissions`) and handles a refusal.
3. On Android 11 and later, also declares the provider so the system lets it resolve it:

   ```xml
   <queries>
       <provider android:authorities="io.sonora.player.api" />
   </queries>
   ```

For testing, grant it from adb: `adb shell pm grant <client.package> io.sonora.permission.EXTERNAL_CONTROL`.

The adb shell and root do not need the permission, only the switch.

## Calls

`ContentResolver.call(uri, method, arg, extras)` with `uri = content://<applicationId>.api`. The `arg` is ignored; all
arguments go in `extras`. A value may be a native type (`Long`, `Int`, `Double`, `Boolean`, `String`, arrays and lists of
them) or text, which is what a shell sends: `"12"` reads as the integer 12, `"true"` as true, `"1,2,3"` as three ids.
Extras a method does not use are ignored, except that any text among them that looks like a URI or a file path is
rejected first (`location_rejected`).

### The result

Every call returns a `Bundle`, whether it worked or not (only a denial throws):

| Key | Type | When |
|---|---|---|
| `ok` | boolean | always |
| `api_version` | int | always |
| `error` | string | on failure: the stable code, see [Errors](#errors) |
| `error_message` | string | on failure: English text for people; it never repeats what the caller sent |
| `error_argument` | string | on failure, when one argument is at fault |
| (payload) | | on success, the keys in the table below |

A command returns once it has been handed to the player; the state you read straight away may not show it yet.

### Methods

Indices refer to the rows of the `queue` query. "id" is a library id taken from a query.

| Method | Arguments | Success payload |
|---|---|---|
| `play`, `pause`, `toggle`, `next`, `previous` | none | none |
| `seek` | `position_ms` (integer, 0 or more) | none |
| `shuffle` | `enabled` (boolean) | none |
| `repeat` | `mode`: `off`, `all` or `one` | none |
| `queue_add` | `ids` (1 to 500 track ids); `placement`: `end` (default), `next` or `now` (right after the current track and play it) | `count` (tracks added) |
| `queue_remove` | `index` (not the current item) | none |
| `queue_move` | `from`, `to` (both upcoming items, after the current one) | none |
| `queue_clear_upcoming` | none | none |
| `queue_skip_to` | `index` | none |
| `play_album`, `play_artist`, `play_playlist` | `id`; optional `shuffle` (boolean, default false), `start_index` (default 0) | `count` (tracks queued) |
| `play_search` | `q` (1 to 200 characters); optional `shuffle`, `start_index` | `count` |
| `play_tracks` | `ids` (1 to 500 track ids, in order); optional `shuffle`, `start_index` | `count` |
| `sleep_start` | `minutes` (1 to 720) or `mode`: `end_of_song` or `end_of_album` (not both) | none |
| `sleep_extend` | `minutes` (1 to 720) | none |
| `sleep_cancel` | none | none |
| `crossfade` | `seconds` (0 to 12; 0 is off) | `seconds` (applied) |
| `speed` | `speed` (factor, 0.5 to 2.0, snapped to steps of 0.05) | `speed` (applied factor, double) |
| `dj_mode` | `enabled` (boolean) | none |

Notes:

- `play_*` replace the queue and start playing. `start_index` is the position, in the order the tracks were resolved and
  before any shuffle, of the track to start from. Album tracks play in disc and track order; an artist's in library
  order; a playlist's in playlist order, skipping tracks no longer in the library.
- `play_search` plays the tracks whose title, artist or album match `q`.
- Unknown ids and indices out of range fail without changing anything.

### Errors

A failed call returns `ok = false` with one of these codes (the same codes start the message of a failed query):

| `error` | Meaning |
|---|---|
| `unknown_method` | The method is not in the table above. |
| `unknown_query` | The query path is not one of those below. |
| `missing_argument` | A required argument is absent (`error_argument` names it). |
| `bad_argument_type` | The argument is not of the expected kind (`integer`, `number`, `boolean`, `string`, `ids`). |
| `argument_out_of_range` | The kind is right but the value is outside what is allowed (an index past the queue, `limit` below 1, too many ids...). |
| `bad_argument_value` | Not one of the accepted values, or it clashes with another argument (an unknown `mode`, removing the current item, `minutes` with `mode`, an unknown projection column). |
| `location_rejected` | A text value looks like a URI or a file path. |
| `not_found` | No track, album, artist or playlist has that id. |
| `nothing_to_do` | The command is valid but there is nothing to do it with: a search with no match, a playlist with no playable track, `sleep_extend` with no timer running. |
| `failed` | The command failed inside Sonora. No detail is given. Also the answer to a call or query made on Sonora's own main thread: the work runs there, so the provider refuses at once instead of blocking it. |
| `playback_unavailable` | The playback service did not connect within 6 seconds, so a command or a read of the player could not be trusted. Try again. |
| `timeout` | The call did not finish in 15 seconds. A command already handed to the player may still have taken effect. |

## Queries

`ContentResolver.query(uri, projection, ...)` with `content://<applicationId>.api/<path>`. Parameters go in the Uri's
query string; the selection and sort arguments are ignored. `projection` is honoured: pass column names to get only
those, in that order. All paged paths accept:

- `limit`: rows per page, default 50, at most 200 (a larger value is brought down to 200), at least 1.
- `offset`: rows to skip, default 0.

Paged cursors carry the paging in `Cursor.getExtras()`: `total` (rows in the whole result), `limit`, `offset`
(the page actually used), `has_more` (boolean) and `api_version`. `adb shell content query` prints rows only, so there
page until a page has fewer than `limit` rows.

| Path | Extra parameters | Rows |
|---|---|---|
| `state` | none | one row: the player and the settings the API can change |
| `queue` | | the play queue in play order |
| `tracks` | `q`: search text (title, artist or album; 200 characters at most; blank means no search) | every song by title |
| `albums` | | albums by title |
| `artists` | | artists by name |
| `playlists` | | playlists |
| `albums/<id>/tracks` | | an album's songs in disc and track order |
| `artists/<id>/tracks` | | an artist's songs |
| `playlists/<id>/tracks` | | a playlist's songs in order, those still in the library |

Booleans are 0 or 1, ids and counts are integers, a value the library does not have is null. Read columns by name, not by
position.

| Query | Columns |
|---|---|
| tracks | `id`, `title`, `artist`, `album`, `album_id`, `artist_id`, `duration_ms`, `track_number`, `disc_number`, `year` |
| albums | `id`, `title`, `artist`, `artist_id`, `year`, `track_count` |
| artists | `id`, `name`, `album_count`, `track_count` |
| playlists | `id`, `name`, `description`, `track_count` (counts every entry, including songs no longer in the library) |
| queue | `index`, `queue_item_id`, `is_current`, then the track columns |
| state | `api_version`, `is_playing`, `position_ms`, `duration_ms`, `shuffle`, `repeat` (`off`, `all`, `one`), `current_index` (-1 when nothing), `queue_size`, `track_id`, `track_title`, `track_artist`, `track_album`, `track_album_id`, `track_artist_id`, `track_duration_ms` (the `track_*` columns are null when nothing is loaded), `sleep_timer_mode` (`minutes`, `end_of_song`, `end_of_album`; null when no timer), `sleep_timer_remaining_ms` (null when unknown), `crossfade_seconds`, `speed` (factor), `dj_mode` |

A failed query throws instead of returning a cursor, with a message that starts with the error code:

- `IllegalArgumentException` for a mistake of the caller: `unknown_query`, `missing_argument`, `bad_argument_type`,
  `argument_out_of_range`, `bad_argument_value`, `location_rejected`, `not_found`.
- `IllegalStateException` for `failed`, `timeout`, `playback_unavailable` and `nothing_to_do`.

`state` and `queue` first wait up to 6 seconds for the playback service to connect, as the commands that act on the
player do. `crossfade`, `speed`, `dj_mode`, the sleep timer and the library lists do not wait for it.

## Examples with adb

The adb shell is allowed once the switch is on; it needs no permission. Quote the whole remote command so that `&` and
spaces reach the device intact. `--extra` takes `key:type:value` with the types `s` (string), `i` (int), `l` (long),
`f` (float), `d` (double) and `b` (boolean).

```sh
URI=content://io.sonora.player.api

# Commands
adb shell "content call --uri $URI --method play"
adb shell "content call --uri $URI --method seek --extra position_ms:l:90000"
adb shell "content call --uri $URI --method repeat --extra mode:s:all"
adb shell "content call --uri $URI --method queue_add --extra ids:s:12,40,41 --extra placement:s:next"
adb shell "content call --uri $URI --method play_album --extra id:l:7 --extra shuffle:b:true"
adb shell "content call --uri $URI --method play_search --extra 'q:s:dark side'"
adb shell "content call --uri $URI --method sleep_start --extra minutes:i:30"
adb shell "content call --uri $URI --method speed --extra speed:d:1.25"
# Result: Bundle[{ok=true, api_version=1, speed=1.25}]

# Reads
adb shell "content query --uri $URI/state"
adb shell "content query --uri $URI/queue"
adb shell "content query --uri '$URI/tracks?q=love&limit=20&offset=0' --projection id:title:artist"
adb shell "content query --uri $URI/albums/7/tracks"
```

## A Kotlin client

```kotlin
private val api = Uri.parse("content://io.sonora.player.api")

/** Runs a command. Returns the result bundle; check `ok` and `error`. */
fun ContentResolver.sonora(method: String, extras: Bundle = Bundle.EMPTY): Bundle? =
    call(api, method, null, extras)

fun playAlbum(resolver: ContentResolver, albumId: Long): Boolean {
    val result = try {
        resolver.sonora("play_album", bundleOf("id" to albumId, "shuffle" to true))
    } catch (denied: SecurityException) {
        return false // the switch is off, or this app lacks the permission
    }
    // null: Sonora is not installed. A failure is ok = false with a stable `error` code.
    return result?.getBoolean("ok") == true
}

data class Song(val id: Long, val title: String, val artist: String)

fun search(resolver: ContentResolver, text: String): List<Song> {
    val uri = api.buildUpon().appendPath("tracks").appendQueryParameter("q", text).appendQueryParameter("limit", "50").build()
    val cursor = try {
        resolver.query(uri, arrayOf("id", "title", "artist"), null, null, null)
    } catch (e: IllegalArgumentException) {
        return emptyList() // message starts with the error code, e.g. "argument_out_of_range: ..."
    } ?: return emptyList()
    cursor.use {
        val hasMore = it.extras.getBoolean("has_more") // ask the next page with offset when true
        return buildList { while (it.moveToNext()) add(Song(it.getLong(0), it.getString(1), it.getString(2))) }
    }
}
```

Call from a background thread: the provider answers on a binder thread and a call can take a few seconds when
Sonora has to start. Code inside Sonora's own process must also call from a background thread: on the main thread the call is not run
and fails at once with `failed`.

## Versioning

`API_VERSION` is 1. It is returned in every call result (`api_version`), in the extras of every cursor and in the
`state` row, so a client can check it.

- It goes up only when a client written for an older version could break: a method, argument, query path or column
  removed or renamed, or a value's meaning changed.
- It does not go up for additions: a new method, an optional argument, a new query path, a new column or a new payload
  key. So a client must ignore keys and columns it does not know, and read columns by name.
- Error codes are part of the API and stable; `error_message` is for people and may change.
- Limits (page size 50 by default and 200 at most, 500 ids per command, 200 characters of search text, 720 minutes of
  sleep timer) may be raised without a version bump.
