package io.sonora.core.cast.handover

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import org.junit.Test

class CastHandoverTest {

    private class FakeServer(var startable: Boolean = true) : CastServerControl {
        val started = mutableListOf<InetAddress>()
        var stops = 0
        val running get() = started.size > stops

        override fun start(address: InetAddress): Boolean {
            if (!startable) return false
            started += address
            return true
        }

        override fun stop() {
            stops++
        }
    }

    private val wifi = InetAddress.getByName("192.168.1.20")
    private var address: InetAddress? = wifi
    private val server = FakeServer()
    private var sessionEnds = 0
    private val handover = CastHandover(wifiAddress = { address }, server = server, endCastSession = { sessionEnds++ })

    @Test
    fun `casting serves the queue from the Wi-Fi address`() {
        assertThat(handover.toCast()).isTrue()

        assertThat(server.started).containsExactly(wifi)
        assertThat(handover.isCasting).isTrue()
        assertThat(sessionEnds).isEqualTo(0)
    }

    @Test
    fun `without Wi-Fi the queue stays on the phone and the session ends`() {
        address = null

        assertThat(handover.toCast()).isFalse()

        assertThat(server.started).isEmpty()
        assertThat(handover.isCasting).isFalse()
        assertThat(sessionEnds).isEqualTo(1)
    }

    @Test
    fun `a server that cannot start keeps the queue on the phone`() {
        server.startable = false

        assertThat(handover.toCast()).isFalse()

        assertThat(sessionEnds).isEqualTo(1)
        assertThat(handover.isCasting).isFalse()
    }

    @Test
    fun `returning from a cast takes its queue and position, paused, and stops serving`() {
        handover.toCast()

        assertThat(handover.toPhone()).isEqualTo(ToPhone.TAKE_CAST_STATE_PAUSED)

        assertThat(server.running).isFalse()
        assertThat(handover.isCasting).isFalse()
    }

    @Test
    fun `returning from an aborted cast keeps the phone's own queue`() {
        address = null
        handover.toCast()

        assertThat(handover.toPhone()).isEqualTo(ToPhone.KEEP_PHONE_STATE)

        assertThat(server.stops).isEqualTo(0)
    }

    @Test
    fun `a handover to the phone without a cast keeps the phone's queue`() {
        assertThat(handover.toPhone()).isEqualTo(ToPhone.KEEP_PHONE_STATE)
        assertThat(server.stops).isEqualTo(0)
    }

    @Test
    fun `losing Wi-Fi while casting ends the session once`() {
        handover.toCast()

        handover.onWifiLost()
        handover.onWifiLost()

        assertThat(sessionEnds).isEqualTo(1)
        // The receiver may still be reached until the session really ends.
        assertThat(server.running).isTrue()
        assertThat(handover.toPhone()).isEqualTo(ToPhone.TAKE_CAST_STATE_PAUSED)
        assertThat(server.running).isFalse()
    }

    @Test
    fun `losing Wi-Fi on the phone changes nothing`() {
        handover.onWifiLost()

        assertThat(sessionEnds).isEqualTo(0)
        assertThat(server.started).isEmpty()
    }

    @Test
    fun `a second cast starts a new server session`() {
        handover.toCast()
        handover.toPhone()
        val other = InetAddress.getByName("10.0.0.7")
        address = other

        assertThat(handover.toCast()).isTrue()

        assertThat(server.started).containsExactly(wifi, other).inOrder()
        assertThat(server.running).isTrue()
    }

    @Test
    fun `a cast that comes back after a failed one works`() {
        address = null
        handover.toCast()
        handover.toPhone()
        address = wifi

        assertThat(handover.toCast()).isTrue()
        assertThat(handover.isCasting).isTrue()
    }

    @Test
    fun `releasing while casting stops the server`() {
        handover.toCast()

        handover.release()

        assertThat(server.running).isFalse()
        assertThat(handover.isCasting).isFalse()
    }

    @Test
    fun `releasing on the phone stops nothing`() {
        handover.release()

        assertThat(server.stops).isEqualTo(0)
    }
}
