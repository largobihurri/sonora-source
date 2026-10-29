package io.sonora.core.watchsync.remote

/** The names the phone app and the watch app share for the remote (messages over the Wearable Data Layer's `MessageClient`). */
object RemotePaths {
    /** Declared by the phone app (`android_wear_capabilities`); the watch finds the phone by it. */
    const val PHONE_CAPABILITY = "sonora_phone"

    /** The one path every remote message travels on, both ways; the version is the protocol version. */
    const val MESSAGE_PATH = "/sonora/remote/v1"

    /** What the phone's message listener filters on (any version of the remote). */
    const val MESSAGE_PATH_PREFIX = "/sonora/remote"
}

/** The numbers both apps must agree on. */
object RemoteLimits {
    /** Below the Data Layer's 100 KB cap on one message, with room to spare. */
    const val MAX_MESSAGE_BYTES = 90_000

    /** The most queue entries one state carries (the playing song and the upcoming ones). */
    const val MAX_QUEUE_ITEMS = 100

    /** The longest title or artist that travels; a longer one is cut. */
    const val MAX_TEXT_CHARS = 80

    /** The most volume steps one command may ask for (a crown turn is coalesced into small batches). */
    const val MAX_VOLUME_STEPS_PER_MESSAGE = 4

    /** The most volume steps up the phone accepts in [VOLUME_WINDOW_MS], whatever the watch sends. */
    const val MAX_VOLUME_RAISE_PER_WINDOW = 6
    const val VOLUME_WINDOW_MS = 1_000L

    /** How long the phone keeps pushing after the last subscribe. */
    const val LEASE_MS = 60_000L

    /** How often the watch renews its subscription (which also resyncs its picture of the phone). */
    const val RENEW_MS = 20_000L

    /** The watch calls the phone unreachable when nothing came for this long while subscribed. */
    const val STALE_MS = 45_000L

    /** How long the phone remembers that a node is one of the user's watches. */
    const val TRUST_TTL_MS = 60_000L

    /** A position this far from where playing would have put it is a seek and is pushed. */
    const val POSITION_JUMP_MS = 1_500L
}
