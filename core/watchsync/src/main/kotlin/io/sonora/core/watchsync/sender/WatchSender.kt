package io.sonora.core.watchsync.sender

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.NotEnoughSpaceException
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.Sha256
import io.sonora.core.watchsync.SyncConnector
import io.sonora.core.watchsync.SyncLimits
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.TrackEncodingException
import io.sonora.core.watchsync.protocol.Frame
import io.sonora.core.watchsync.protocol.FrameReader
import io.sonora.core.watchsync.protocol.FrameWriter
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.runBlockingIo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The phone's end of the sync: sends songs to the watch, reads what is on it and removes songs. Each operation opens a
 * connection through [connector] and, when it drops or the watch is not reachable, opens another and carries on: what
 * the watch already received is not sent again (it names the byte to resume from), and a finished encoding is not
 * redone (the [encoder] keeps it until the watch confirms the song).
 *
 * Plain blocking streams on [io]; cancelling an operation closes the connection at once.
 *
 * @throws ProtocolException from every operation when the watch speaks something else; not retried.
 */
class WatchSender(
    private val connector: SyncConnector,
    private val encoder: TrackEncoder,
    private val config: SenderConfig = SenderConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The watch's free space and the songs it holds. */
    suspend fun readState(): WatchState = retrying { withConnection { query() } }

    /** Asks the watch to delete [trackIds]; returns the ids it confirmed gone. */
    suspend fun remove(trackIds: List<String>): List<String> = retrying {
        withConnection {
            send(SyncMessage.Remove(trackIds))
            var reply: SyncMessage.Removed? = null
            while (reply == null) {
                val message = next()
                if (message is SyncMessage.Removed) reply = message
            }
            reply.trackIds
        }
    }

    /**
     * Encodes [candidates] and sends the ones the watch does not hold, one at a time, in order. [onProgress] is called from
     * background threads, one call at a time.
     *
     * @throws NotEnoughSpaceException when the songs cannot fit (before any encoding), or the watch runs out of room.
     * @throws IOException when the watch stays unreachable or keeps dropping the connection.
     */
    suspend fun send(candidates: List<SendCandidate>, onProgress: (SendProgress) -> Unit = {}): SendReport {
        val job = SendJob(candidates.distinctBy { it.id }, onProgress)
        retrying { attempt -> withConnection { job.attempt(this, attempt) } }
        return job.report()
    }

    private suspend fun <T> retrying(block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block(attempt)
            } catch (e: IOException) {
                if (attempt >= config.maxAttempts) throw e
                delay(config.retryDelayMs(attempt))
            }
        }
    }

    private suspend fun <T> withConnection(block: suspend Connection.() -> T): T {
        val streams = connector.connect()
        try {
            return coroutineScope {
                val connection = Connection(streams, this)
                try {
                    connection.handshake()
                    val result = connection.block()
                    try {
                        connection.send(SyncMessage.End)
                    } catch (_: IOException) {
                        // The work is done; a connection that dies on the way out costs nothing.
                    }
                    result
                } finally {
                    streams.close()
                }
            }
        } finally {
            streams.close()
        }
    }

    /** One open connection: framed writes, and a reader coroutine that queues what the watch says. */
    private inner class Connection(val streams: SyncStreams, scope: CoroutineScope) {
        private val writer = FrameWriter(streams.output)
        private val reader = FrameReader(streams.input)
        private val inbox = Channel<SyncMessage>(Channel.UNLIMITED)

        /** What songs may use on the watch, as of the last [query]. */
        var usableBytes = 0L
            private set

        init {
            scope.launch(io) { pump() }
        }

        private fun pump() {
            try {
                while (true) {
                    val frame = reader.read() ?: break
                    if (frame !is Frame.Control) throw ProtocolException("The watch sent song data")
                    inbox.trySend(frame.message)
                }
                inbox.close()
            } catch (e: Throwable) {
                inbox.close(e)
            }
        }

        suspend fun send(message: SyncMessage) = runBlockingIo(streams, io) { writer.writeControl(message) }

        /** The next message from the watch. @throws IOException when the connection closes or the watch is silent too long. */
        suspend fun next(): SyncMessage = try {
            withTimeout(config.replyTimeoutMs) { inbox.receive() }
        } catch (_: TimeoutCancellationException) {
            throw IOException("The watch did not answer in ${config.replyTimeoutMs} ms")
        } catch (_: ClosedReceiveChannelException) {
            throw IOException("The connection to the watch closed")
        }

        suspend fun handshake() {
            send(SyncMessage.Hello())
            val reply = next()
            if (reply !is SyncMessage.Hello) throw ProtocolException("The watch did not say hello")
            if (reply.version != SyncLimits.PROTOCOL_VERSION) {
                throw ProtocolException("The watch app speaks protocol ${reply.version}, this phone ${SyncLimits.PROTOCOL_VERSION}")
            }
        }

        suspend fun query(): WatchState {
            send(SyncMessage.Query)
            var space: SyncMessage.FreeSpace? = null
            val tracks = ArrayList<ManifestTrack>()
            var listed = false
            // The song list may come in several pages; the last one has no `more`.
            while (space == null || !listed) {
                when (val message = next()) {
                    is SyncMessage.FreeSpace -> space = message
                    is SyncMessage.Contents -> {
                        tracks += message.tracks
                        listed = !message.more
                    }
                    else -> Unit
                }
            }
            return WatchState(space.freeBytes, space.totalBytes, space.reserveBytes, tracks)
                .also { usableBytes = it.usableBytes }
        }

        /** The watch's reply to the offer of [trackId]: [SyncMessage.Have], [SyncMessage.Request] or [SyncMessage.Reject]. */
        suspend fun awaitAnswer(trackId: String): SyncMessage {
            while (true) {
                when (val message = next()) {
                    is SyncMessage.Have -> if (message.trackId == trackId) return message
                    is SyncMessage.Request -> if (message.trackId == trackId) return message
                    is SyncMessage.Reject -> if (message.trackId == trackId || message.trackId == null) return message
                    else -> Unit
                }
            }
        }

        /**
         * Writes [file] from [offset] and returns the watch's verdict, [SyncMessage.Done] or [SyncMessage.Failed].
         * [onConfirmed] gets the bytes the watch reports on disk.
         */
        suspend fun stream(file: File, manifest: ManifestTrack, offset: Long, onConfirmed: (Long) -> Unit): SyncMessage {
            var verdict: SyncMessage? = null
            runBlockingIo(streams, io) {
                RandomAccessFile(file, "r").use { source ->
                    source.seek(offset)
                    val buffer = ByteArray(config.chunkBytes)
                    var position = offset
                    while (position < manifest.sizeBytes && verdict == null) {
                        val wanted = minOf(buffer.size.toLong(), manifest.sizeBytes - position).toInt()
                        val count = source.read(buffer, 0, wanted)
                        if (count < 0) throw IOException("The encoded file ended early")
                        writer.writeData(buffer, 0, count)
                        position += count
                        verdict = drain(manifest.id, onConfirmed)
                    }
                    writer.flush()
                }
            }
            while (true) {
                verdict?.let { return it }
                when (val message = next()) {
                    is SyncMessage.Progress -> if (message.trackId == manifest.id) onConfirmed(message.receivedBytes)
                    is SyncMessage.Done -> if (message.trackId == manifest.id) verdict = message
                    is SyncMessage.Failed -> if (message.trackId == manifest.id) verdict = message
                    else -> Unit
                }
            }
        }

        /** Reads what the watch has already said, without waiting. */
        private fun drain(trackId: String, onConfirmed: (Long) -> Unit): SyncMessage? {
            while (true) {
                val message = inbox.tryReceive().getOrNull() ?: return null
                when {
                    message is SyncMessage.Progress && message.trackId == trackId -> onConfirmed(message.receivedBytes)
                    message is SyncMessage.Done && message.trackId == trackId -> return message
                    message is SyncMessage.Failed && message.trackId == trackId -> return message
                }
            }
        }
    }

    /** One send across however many connections it takes. */
    private inner class SendJob(private val candidates: List<SendCandidate>, private val onProgress: (SendProgress) -> Unit) {
        private val sent = ArrayList<SentTrack>()
        private val failed = ArrayList<FailedTrack>()
        private val skipped = LinkedHashMap<String, SendCandidate>()
        private var attempts = 0

        /** The songs to deliver, fixed by the first connection; later ones only carry on from where it stopped. */
        private var order: List<SendCandidate>? = null

        fun report() = SendReport(sent.toList(), skipped.values.toList(), failed.toList(), attempts)

        suspend fun attempt(connection: Connection, attempt: Int) {
            attempts = attempt
            val watch = connection.query()
            val held = watch.tracks.mapTo(HashSet()) { it.id }
            val songs = order ?: run {
                val plan = SendPlanner.plan(candidates, watch.tracks, watch.freeSpace)
                // Only the first connection checks the estimate: after a drop the watch holds part of the bytes already.
                (plan.space as? SpaceCheck.NotEnough)?.let { throw NotEnoughSpaceException(it.neededBytes, it.availableBytes) }
                plan.alreadyOnWatch.forEach { skipped[it.id] = it }
                plan.toSend.also { order = it }
            }
            val finishedIds = (sent.map { it.id } + failed.map { it.id }).toHashSet()
            val weights = songs.map { it.durationMs }
            for ((index, song) in songs.withIndex()) {
                if (song.id in finishedIds || song.id in skipped) continue
                if (song.id in held) {
                    // It arrived on an earlier connection whose confirmation was lost.
                    skipped[song.id] = song
                    encoder.release(song)
                    continue
                }
                deliver(connection, song, index, songs.size, weights)
            }
        }

        private suspend fun deliver(connection: Connection, song: SendCandidate, index: Int, count: Int, weights: List<Long>) {
            fun publish(phase: SendProgress.Phase, fraction: Float) = onProgress(
                SendProgress(
                    phase = phase,
                    trackNumber = index + 1,
                    trackCount = count,
                    trackId = song.id,
                    title = song.title,
                    phaseFraction = fraction,
                    overallFraction = SendProgressMath.overall(weights, index, SendProgressMath.songFraction(phase, fraction)),
                ),
            )

            publish(SendProgress.Phase.ENCODING, 0f)
            val file = try {
                encoder.encode(song) { publish(SendProgress.Phase.ENCODING, it) }
            } catch (_: TrackEncodingException) {
                failed += FailedTrack(song.id, song.title, SyncError.ENCODING_FAILED)
                return
            }
            if (file.length() <= 0) {
                // An Opus file always has headers: an empty one is a failed encoding, not a song to offer.
                failed += FailedTrack(song.id, song.title, SyncError.ENCODING_FAILED)
                encoder.release(song)
                return
            }
            val manifest = manifestOf(song, file)
            var retries = 0
            while (true) {
                val started = clock()
                connection.send(SyncMessage.Offer(manifest))
                when (val answer = connection.awaitAnswer(manifest.id)) {
                    is SyncMessage.Have -> {
                        skipped[song.id] = song
                        encoder.release(song)
                        return
                    }
                    is SyncMessage.Reject -> {
                        if (answer.trackId == null) throw ProtocolException("The watch refused the session: ${answer.reason}")
                        if (answer.reason == SyncError.NO_SPACE) throw NotEnoughSpaceException(manifest.sizeBytes, connection.usableBytes)
                        failed += FailedTrack(song.id, song.title, answer.reason)
                        encoder.release(song)
                        return
                    }
                    is SyncMessage.Request -> {
                        if (!ResumeOffsets.isValid(answer.offset, manifest.sizeBytes)) {
                            throw ProtocolException("The watch asked to resume at ${answer.offset} of ${manifest.sizeBytes} bytes")
                        }
                        val verdict = connection.stream(file, manifest, answer.offset) { received ->
                            publish(SendProgress.Phase.SENDING, SendProgressMath.fraction(received, manifest.sizeBytes))
                        }
                        if (verdict is SyncMessage.Done) {
                            sent += SentTrack(
                                id = song.id,
                                title = song.title,
                                sizeBytes = manifest.sizeBytes,
                                transferredBytes = manifest.sizeBytes - answer.offset,
                                elapsedMs = clock() - started,
                                resumedFrom = answer.offset,
                            )
                            encoder.release(song)
                            publish(SendProgress.Phase.SENDING, 1f)
                            return
                        }
                        val reason = (verdict as SyncMessage.Failed).reason
                        if (reason == SyncError.CHECKSUM_MISMATCH && retries++ < config.trackRetries) continue
                        failed += FailedTrack(song.id, song.title, reason)
                        encoder.release(song)
                        return
                    }
                    else -> throw ProtocolException("Unexpected answer to an offer")
                }
            }
        }

        private fun manifestOf(song: SendCandidate, file: File) = ManifestTrack(
            id = song.id,
            title = song.title,
            artist = song.artist,
            albumArtist = song.albumArtist,
            album = song.album,
            trackNumber = song.trackNumber,
            discNumber = song.discNumber,
            durationMs = song.durationMs,
            sizeBytes = file.length(),
            sha256 = Sha256.hexOfFile(file),
        )
    }
}
