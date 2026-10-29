package io.sonora.core.cast.server

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** A file opened for sending: [length] bytes (-1 when unknown) of [mimeType], read from [input] after [seekTo]. */
class OpenedFile(val length: Long, val mimeType: String, val input: InputStream, private val seek: (Long) -> Unit) : Closeable {
    /** Moves [input] to [offset] bytes from the start of the file. */
    fun seekTo(offset: Long) = seek(offset)

    override fun close() = input.close()
}

/** Opens what the server is asked for; null when the file is gone or unreadable. */
fun interface FileOpener {
    fun open(served: Served): OpenedFile?
}

/**
 * The minimal HTTP/1.1 server a Cast receiver streams the queue from: GET and HEAD only, one request per connection
 * (`Connection: close`), single byte ranges (so the receiver can seek), and only the paths [urls] resolves (the
 * session token plus an entry still in the queue); everything else is a 404 without touching any file.
 *
 * It listens on [address] (the phone's Wi-Fi address, never all interfaces) on a random free port, from [start] to
 * [stop]. Connections are served on a few daemon threads; [stop] closes the listening socket and every open
 * connection, so a stream in progress ends at once.
 */
class CastHttpServer(
    private val address: InetAddress,
    @Volatile var opener: FileOpener,
    private val workers: Int = 4,
) {
    /** The paths this server answers; null answers nothing. Set after [start], once the URLs can name the port. */
    @Volatile var urls: CastQueueUrls? = null

    private var listener: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val connections: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    /** The port it listens on (0 before [start]). */
    val port: Int get() = listener?.localPort ?: 0

    /** Starts listening and returns the port; throws [IOException] when the address cannot be bound. */
    @Synchronized
    fun start(): Int {
        listener?.let { return it.localPort }
        val socket = ServerSocket(0, BACKLOG, address)
        val executor = Executors.newFixedThreadPool(workers) { task -> Thread(task, "SonoraCastHttp").apply { isDaemon = true } }
        listener = socket
        pool = executor
        Thread({ accept(socket, executor) }, "SonoraCastAccept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** Stops listening and drops every connection; a later [start] listens again on a new port. */
    @Synchronized
    fun stop() {
        listener?.close()
        listener = null
        pool?.shutdownNow()
        pool = null
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun accept(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // closed by stop()
            }
            connections += client
            try {
                executor.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                connections -= client
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = READ_TIMEOUT_MS
                val output = it.getOutputStream().buffered()
                respond(it.getInputStream(), output)
                output.flush()
            }
        } catch (_: IOException) {
            // The receiver closed the connection (a seek drops the previous request) or the server stopped.
        } finally {
            connections -= client
        }
    }

    private fun respond(input: InputStream, output: OutputStream) {
        val request = HttpRequestHead.read(input) ?: return output.status(400, "Bad Request")
        if (request.method != "GET" && request.method != "HEAD") {
            return output.status(405, "Method Not Allowed", "Allow" to "GET, HEAD")
        }
        val served = urls?.resolve(request.path) ?: return output.status(404, "Not Found")
        val file = try {
            opener.open(served)
        } catch (_: Exception) {
            null // a revoked grant (SecurityException) or a deleted file
        } ?: return output.status(404, "Not Found")
        file.use { send(it, request, output) }
    }

    private fun send(file: OpenedFile, request: HttpRequestHead, output: OutputStream) {
        val common = buildList {
            add("Content-Type" to file.mimeType)
            if (file.length >= 0) add("Accept-Ranges" to "bytes")
        }
        val (first, count) = when (val range = HttpRange.parse(request.header("range"), file.length)) {
            RangeRequest.Unsatisfiable -> return output.status(416, "Range Not Satisfiable", "Content-Range" to "bytes */${file.length}")
            RangeRequest.Whole -> {
                output.head(200, "OK", common + listOfNotNull(file.length.takeIf { it >= 0 }?.let { "Content-Length" to "$it" }))
                0L to file.length
            }
            is RangeRequest.Part -> {
                output.head(206, "Partial Content", common + listOf("Content-Range" to range.contentRange(file.length), "Content-Length" to "${range.length}"))
                range.first to range.length
            }
        }
        if (request.method == "HEAD") return
        if (first > 0) file.seekTo(first)
        copy(file.input, output, count)
    }

    /** Copies [count] bytes (everything when negative) from [input] to [output]. */
    private fun copy(input: InputStream, output: OutputStream, count: Long) {
        val buffer = ByteArray(BUFFER_BYTES)
        var left = if (count < 0) Long.MAX_VALUE else count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            left -= read
        }
    }

    private fun OutputStream.status(code: Int, reason: String, vararg headers: Pair<String, String>) =
        head(code, reason, headers.toList() + ("Content-Length" to "0"))

    private fun OutputStream.head(code: Int, reason: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            // Default Media Receiver players may fetch with CORS; the URL's token is what guards the file.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private companion object {
        const val BACKLOG = 16
        const val READ_TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** A request's line and headers (`Range` is the only header this server reads). */
internal class HttpRequestHead(val method: String, val path: String, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_HEAD_BYTES = 8 * 1024

        /** Reads up to the blank line; null when the request is malformed or its head too long. */
        fun read(input: InputStream): HttpRequestHead? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/") || !parts[1].startsWith("/")) return null
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return HttpRequestHead(parts[0], parts[1], headers)
        }

        private fun readHead(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (bytes.size() < MAX_HEAD_BYTES) {
                val byte = input.read()
                if (byte < 0) return null
                bytes.write(byte)
                matched = when {
                    byte == '\r'.code && matched % 2 == 0 -> matched + 1
                    byte == '\n'.code && matched % 2 == 1 -> matched + 1
                    byte == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) return bytes.toString(Charsets.US_ASCII.name()).dropLast(4)
            }
            return null
        }
    }
}
