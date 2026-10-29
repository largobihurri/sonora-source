package io.sonora.core.cast.server

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The server on the loopback interface, spoken to over real sockets the way a Cast receiver does. */
class CastHttpServerTest {

    private val audio = ByteArray(1_000) { (it % 251).toByte() }
    private val art = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val token = CastToken("0123456789abcdef0123456789abcdef")
    private var unknownLength = false
    private val opened = mutableListOf<String>()

    private val opener = FileOpener { served ->
        when (served) {
            is Served.Audio -> {
                opened += served.uri
                val stream = ByteArrayInputStream(audio)
                OpenedFile(if (unknownLength) -1 else audio.size.toLong(), served.mimeType, stream) { stream.reset(); stream.skip(it) }
            }
            is Served.Artwork -> OpenedFile(art.size.toLong(), "image/png", ByteArrayInputStream(art)) {}
        }
    }

    private lateinit var server: CastHttpServer
    private lateinit var audioPath: String
    private lateinit var artPath: String

    @Before
    fun setUp() {
        server = CastHttpServer(InetAddress.getLoopbackAddress(), opener)
        val port = server.start()
        val urls = CastQueueUrls("127.0.0.1", port, token, now = { 0L })
        server.urls = urls
        val mapped = urls.map("1:1", "content://song", "audio/flac", "file:///art.png")
        audioPath = mapped.audio.substringAfter(":$port")
        artPath = mapped.artwork!!.substringAfter(":$port")
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun `a plain GET sends the whole file`() {
        val response = request("GET $audioPath HTTP/1.1")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers["content-type"]).isEqualTo("audio/flac")
        assertThat(response.headers["content-length"]).isEqualTo("1000")
        assertThat(response.headers["accept-ranges"]).isEqualTo("bytes")
        assertThat(response.body).isEqualTo(audio)
    }

    @Test
    fun `a range request gets that part`() {
        val response = request("GET $audioPath HTTP/1.1", "Range: bytes=100-199")

        assertThat(response.status).isEqualTo(206)
        assertThat(response.headers["content-range"]).isEqualTo("bytes 100-199/1000")
        assertThat(response.headers["content-length"]).isEqualTo("100")
        assertThat(response.body).isEqualTo(audio.copyOfRange(100, 200))
    }

    @Test
    fun `a seek to the end gets the last bytes`() {
        val response = request("GET $audioPath HTTP/1.1", "range: bytes=-10")

        assertThat(response.status).isEqualTo(206)
        assertThat(response.headers["content-range"]).isEqualTo("bytes 990-999/1000")
        assertThat(response.body).isEqualTo(audio.copyOfRange(990, 1_000))
    }

    @Test
    fun `a range past the end is refused with the file length`() {
        val response = request("GET $audioPath HTTP/1.1", "Range: bytes=5000-")

        assertThat(response.status).isEqualTo(416)
        assertThat(response.headers["content-range"]).isEqualTo("bytes */1000")
        assertThat(response.body).isEmpty()
    }

    @Test
    fun `HEAD sends the headers only`() {
        val response = request("HEAD $audioPath HTTP/1.1", "Range: bytes=0-")

        assertThat(response.status).isEqualTo(206)
        assertThat(response.headers["content-length"]).isEqualTo("1000")
        assertThat(response.body).isEmpty()
    }

    @Test
    fun `artwork is served with its own type`() {
        val response = request("GET $artPath HTTP/1.1")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers["content-type"]).isEqualTo("image/png")
        assertThat(response.body).isEqualTo(art)
    }

    @Test
    fun `a file of unknown length is streamed whole, whatever the range`() {
        unknownLength = true

        val response = request("GET $audioPath HTTP/1.1", "Range: bytes=100-199")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers).doesNotContainKey("content-length")
        assertThat(response.headers).doesNotContainKey("accept-ranges")
        assertThat(response.body).isEqualTo(audio)
    }

    @Test
    fun `a wrong token finds nothing and opens nothing`() {
        val response = request("GET ${audioPath.replace(token.value, "f".repeat(32))} HTTP/1.1")

        assertThat(response.status).isEqualTo(404)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `other methods are not allowed`() {
        val response = request("POST $audioPath HTTP/1.1")

        assertThat(response.status).isEqualTo(405)
        assertThat(response.headers["allow"]).isEqualTo("GET, HEAD")
    }

    @Test
    fun `a malformed request is a bad request`() {
        assertThat(request("NONSENSE").status).isEqualTo(400)
    }

    @Test
    fun `a file that cannot be opened is not found`() {
        server.opener = FileOpener { null }

        assertThat(request("GET $audioPath HTTP/1.1").status).isEqualTo(404)
    }

    @Test(expected = ConnectException::class)
    fun `a stopped server accepts nothing`() {
        val port = server.port
        server.stop()

        Socket(InetAddress.getLoopbackAddress(), port).close()
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(line: String, vararg headers: String): Response {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            socket.soTimeout = 5_000
            val head = buildString {
                append(line).append("\r\n")
                append("Host: 127.0.0.1\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            val raw = socket.getInputStream().readBytes()
            val split = raw.indexOf("\r\n\r\n")
            val lines = String(raw, 0, split, Charsets.US_ASCII).split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val parsed = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(status, parsed, raw.copyOfRange(split + 4, raw.size))
        }
    }

    private fun ByteArray.indexOf(text: String): Int {
        val needle = text.toByteArray()
        return (0..size - needle.size).first { start -> needle.indices.all { this[start + it] == needle[it] } }
    }
}
