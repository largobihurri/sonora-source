package io.sonora.core.watchsync

import java.io.IOException

/**
 * A sync failure that retrying cannot fix. Plain [IOException]s (a dropped connection, a full buffer, a timeout) are the
 * ones the sender retries and resumes from; these are not, and they are deliberately not `IOException`s so a catch for
 * the retryable kind never swallows them.
 */
open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The peer sent something that is not the protocol: bytes that are no frame, a message out of order, a newer version. */
class ProtocolException(message: String, cause: Throwable? = null) : SyncException(message, cause)

/** The watch has no room for the songs, whatever it is retried with. */
class NotEnoughSpaceException(val neededBytes: Long, val availableBytes: Long) :
    SyncException("The watch has room for $availableBytes bytes and the songs need $neededBytes")

/** No watch with the Sonora app can be reached right now. */
class WatchNotFoundException(message: String = "No watch with Sonora is connected") : SyncException(message)

/**
 * A song cannot be turned into Opus (unreadable or unsupported source). Thrown by a [io.sonora.core.watchsync.sender.TrackEncoder];
 * the sender reports that song as failed and goes on with the rest.
 */
class TrackEncodingException(message: String, cause: Throwable? = null) : SyncException(message, cause)
