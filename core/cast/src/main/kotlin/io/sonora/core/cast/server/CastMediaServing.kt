package io.sonora.core.cast.server

import android.os.SystemClock
import android.util.Log
import io.sonora.core.cast.handover.CastServerControl
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom

/**
 * One cast server session per cast: [start] listens on the Wi-Fi address on a random port with a new token, and
 * [urls] maps the queue to that session's URLs until [stop] closes the server and forgets them.
 */
class CastMediaServing(private val opener: FileOpener) : CastServerControl {
    private val random = SecureRandom()
    private var server: CastHttpServer? = null

    /** The running session's queue -> URL mapping; null while not serving. */
    @Volatile var urls: CastQueueUrls? = null
        private set

    override fun start(address: InetAddress): Boolean {
        stop()
        val http = CastHttpServer(address, opener)
        val port = try {
            http.start()
        } catch (e: IOException) {
            Log.w(TAG, "Cast server cannot listen on the Wi-Fi address", e)
            return false
        }
        val host = address.hostAddress ?: return false.also { http.stop() }
        val mapping = CastQueueUrls(host, port, CastToken.random(random), SystemClock::elapsedRealtime)
        http.urls = mapping
        server = http
        urls = mapping
        return true
    }

    override fun stop() {
        urls = null
        server?.stop()
        server = null
    }

    private companion object {
        const val TAG = "SonoraCast"
    }
}
