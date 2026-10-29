package io.sonora.core.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Follows the phone's Wi-Fi networks on the main thread: [address] is the IPv4 address a Cast receiver on the same
 * network can reach (the cast server binds to it), and [onLost] fires when the last Wi-Fi network is gone.
 */
internal class WifiMonitor(context: Context, private val onLost: () -> Unit) {
    private val connectivity = context.getSystemService<ConnectivityManager>()
    private val networks = LinkedHashMap<Network, LinkProperties?>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network !in networks) networks[network] = null
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networks[network] = linkProperties
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            if (networks.isEmpty()) onLost()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity?.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper())) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        networks.clear()
    }

    /** The Wi-Fi IPv4 address, or null without Wi-Fi (a receiver could not reach the phone). */
    fun address(): InetAddress? =
        networks.values.firstNotNullOfOrNull { it?.ipv4() } ?: activeWifi()?.let { connectivity?.getLinkProperties(it)?.ipv4() }

    /** The active network when it is Wi-Fi: covers a cast started before the first callback arrived. */
    private fun activeWifi(): Network? {
        val active = connectivity?.activeNetwork ?: return null
        val wifi = connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return active.takeIf { wifi }
    }

    private fun LinkProperties.ipv4(): InetAddress? = linkAddresses.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
}
