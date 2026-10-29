package io.sonora.core.watchsync

/**
 * The names the phone app and the watch app share through the Wearable Data Layer. Both apps have the same
 * application id and signing key, so they see each other's capability and channel.
 */
object WatchSyncPaths {
    /** Declared by the watch app (`android_wear_capabilities`); the phone finds the watch by it. */
    const val WATCH_CAPABILITY = "sonora_watch"

    /** The channel the phone opens for one sync session; the version is the protocol version. */
    const val SYNC_CHANNEL = "/sonora/sync/v1"

    /** What the watch's channel listener filters on (any version of the sync channel). */
    const val SYNC_CHANNEL_PREFIX = "/sonora/sync"
}
