package io.sonora.player.wear.remote

/** How much of the phone the watch is asking for right now. */
enum class RemoteMode {
    /** No remote screen is showing: no subscription, no messages. */
    Off,

    /** Now Playing of the phone: its state without the queue. */
    NowPlaying,

    /** The phone's queue screen: the state with the queue window. */
    Queue,
}

/** The navigation routes of the remote screens. */
object RemoteRoutes {
    const val NOW_PLAYING = "remote"
    const val QUEUE = "remote_queue"
}

/** The mode a screen (by its route) puts the remote in; any other screen leaves it off. */
fun remoteModeFor(route: String?): RemoteMode = when (route) {
    RemoteRoutes.NOW_PLAYING -> RemoteMode.NowPlaying
    RemoteRoutes.QUEUE -> RemoteMode.Queue
    else -> RemoteMode.Off
}

/** The remote talks only while the app is on screen. */
fun effectiveMode(mode: RemoteMode, foreground: Boolean): RemoteMode = if (foreground) mode else RemoteMode.Off
