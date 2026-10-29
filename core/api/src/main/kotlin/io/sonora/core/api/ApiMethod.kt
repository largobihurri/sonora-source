package io.sonora.core.api

/**
 * Every command a caller can run through `call(method, ...)`. [wireName] is the method string callers send; it is
 * part of the API (see [API_VERSION]).
 */
enum class ApiMethod(val wireName: String) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("toggle"),
    NEXT("next"),
    PREVIOUS("previous"),
    SEEK("seek"),
    SHUFFLE("shuffle"),
    REPEAT("repeat"),
    QUEUE_ADD("queue_add"),
    QUEUE_REMOVE("queue_remove"),
    QUEUE_MOVE("queue_move"),
    QUEUE_CLEAR_UPCOMING("queue_clear_upcoming"),
    QUEUE_SKIP_TO("queue_skip_to"),
    PLAY_ALBUM("play_album"),
    PLAY_ARTIST("play_artist"),
    PLAY_PLAYLIST("play_playlist"),
    PLAY_SEARCH("play_search"),
    PLAY_TRACKS("play_tracks"),
    SLEEP_START("sleep_start"),
    SLEEP_EXTEND("sleep_extend"),
    SLEEP_CANCEL("sleep_cancel"),
    CROSSFADE("crossfade"),
    SPEED("speed"),
    DJ_MODE("dj_mode");

    companion object {
        /** The method called [name] (exact, case-sensitive), or null. */
        fun fromWireName(name: String): ApiMethod? = entries.firstOrNull { it.wireName == name }
    }
}
