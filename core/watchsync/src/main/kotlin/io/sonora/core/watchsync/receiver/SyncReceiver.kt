package io.sonora.core.watchsync.receiver

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.SyncLimits
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.protocol.ContentsPaging
import io.sonora.core.watchsync.protocol.Frame
import io.sonora.core.watchsync.protocol.FrameReader
import io.sonora.core.watchsync.protocol.FrameWriter
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.runBlockingIo
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The watch's end of a sync session: answers the phone's queries, receives the songs it offers into the
 * [MusicLibraryStore] (to a partial file, verified, then renamed into place) and removes the ones it asks to remove.
 * Everything that can be decided without Android lives here; the watch app only supplies the streams of the Data Layer
 * channel and the folder.
 *
 * @param onLibraryChanged Called, on the session's thread, after a song was added or songs were removed.
 * @param progressEveryBytes How many new bytes make a [SyncMessage.Progress].
 * @param contentsPageBytes The size at which the song list is split into [SyncMessage.Contents] pages.
 */
class SyncReceiver(
    private val store: MusicLibraryStore,
    private val onLibraryChanged: () -> Unit = {},
    private val progressEveryBytes: Long = 256 * 1024L,
    private val contentsPageBytes: Int = SyncLimits.CONTENTS_PAGE_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val latest = AtomicReference<SyncStreams?>(null)

    /**
     * Serves one connection until the phone ends it or it drops, then closes [streams]. Cancelling stops it at once.
     * Whatever a dropped connection had received stays on disk for the phone to resume.
     *
     * One connection at a time: a new one closes the one before it (the phone reconnected, so the old link is dead even
     * if the channel has not noticed yet) and waits for it to finish, so two never write the same partial file.
     *
     * @throws ProtocolException when the phone sends something that is not the protocol.
     * @throws IOException when the connection breaks inside a message.
     */
    suspend fun serve(streams: SyncStreams) {
        latest.getAndSet(streams)?.close()
        try {
            lock.withLock { runBlockingIo(streams, io) { Session(streams).run() } }
        } finally {
            latest.compareAndSet(streams, null)
            streams.close()
        }
    }

    private inner class Session(streams: SyncStreams) {
        private val reader = FrameReader(streams.input)
        private val writer = FrameWriter(streams.output)
        private var incoming: MusicLibraryStore.IncomingTrack? = null
        private var lastReported = 0L

        /** After a storage failure the data already in flight for that song is dropped, not a protocol error. */
        private var dropData = false

        fun run() {
            store.discardStalePartials(STALE_PARTIAL_MS)
            try {
                while (true) {
                    when (val frame = reader.read() ?: return) {
                        is Frame.Data -> onData(frame.bytes)
                        is Frame.Control -> {
                            dropData = false
                            if (!onMessage(frame.message)) return
                        }
                    }
                }
            } finally {
                incoming?.close()
            }
        }

        /** Returns false when the session is over. */
        private fun onMessage(message: SyncMessage): Boolean {
            incoming?.let {
                // The phone moved on: what arrived stays for a later resume.
                it.close()
                incoming = null
            }
            when (message) {
                is SyncMessage.Hello -> {
                    writer.writeControl(SyncMessage.Hello())
                    return message.version == SyncLimits.PROTOCOL_VERSION
                }
                SyncMessage.Query -> {
                    writer.writeControl(store.space())
                    ContentsPaging.pages(store.contents(), contentsPageBytes).forEach(writer::writeControl)
                }
                is SyncMessage.Offer -> onOffer(message.track)
                is SyncMessage.Remove -> {
                    writer.writeControl(SyncMessage.Removed(store.remove(message.trackIds)))
                    onLibraryChanged()
                }
                SyncMessage.End -> return false
                else -> throw ProtocolException("Unexpected message from the phone: ${message::class.simpleName}")
            }
            return true
        }

        private fun onOffer(track: ManifestTrack) {
            if (track.problem() != null) {
                writer.writeControl(SyncMessage.Reject(track.id, SyncError.INVALID_TRACK))
                return
            }
            if (store.has(track)) {
                writer.writeControl(SyncMessage.Have(track.id))
                return
            }
            val offset = store.partialLength(track)
            if (track.sizeBytes - offset > store.space().usableBytes) {
                writer.writeControl(SyncMessage.Reject(track.id, SyncError.NO_SPACE))
                return
            }
            val started = try {
                store.beginIncoming(track, offset)
            } catch (_: IOException) {
                writer.writeControl(SyncMessage.Reject(track.id, SyncError.STORAGE_ERROR))
                return
            }
            incoming = started
            lastReported = offset
            writer.writeControl(SyncMessage.Request(track.id, offset))
            if (offset == track.sizeBytes) finish(started)
        }

        private fun onData(bytes: ByteArray) {
            val current = incoming
            if (current == null) {
                if (dropData) return
                throw ProtocolException("Data without an offer")
            }
            if (current.received + bytes.size > current.track.sizeBytes) {
                current.discard()
                incoming = null
                throw ProtocolException("More data than the offered song")
            }
            try {
                current.write(bytes, 0, bytes.size)
            } catch (_: IOException) {
                current.discard()
                incoming = null
                dropData = true
                writer.writeControl(SyncMessage.Failed(current.track.id, SyncError.STORAGE_ERROR))
                return
            }
            when {
                current.received == current.track.sizeBytes -> finish(current)
                current.received - lastReported >= progressEveryBytes -> {
                    lastReported = current.received
                    writer.writeControl(SyncMessage.Progress(current.track.id, current.received))
                }
            }
        }

        private fun finish(current: MusicLibraryStore.IncomingTrack) {
            incoming = null
            val reply = try {
                when (current.commit()) {
                    CommitResult.Committed -> {
                        current.close()
                        onLibraryChanged()
                        SyncMessage.Done(current.track.id)
                    }
                    CommitResult.ChecksumMismatch -> SyncMessage.Failed(current.track.id, SyncError.CHECKSUM_MISMATCH)
                }
            } catch (_: IOException) {
                current.discard()
                SyncMessage.Failed(current.track.id, SyncError.STORAGE_ERROR)
            }
            writer.writeControl(reply)
        }
    }

    private companion object {
        /** Partial files untouched for a week are transfers nobody will resume. */
        const val STALE_PARTIAL_MS = 7L * 24 * 3600 * 1000
    }
}
