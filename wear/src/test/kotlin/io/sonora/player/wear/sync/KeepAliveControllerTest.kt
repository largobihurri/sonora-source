package io.sonora.player.wear.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

class KeepAliveControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sessions = MutableStateFlow(0)
    private var idles = 0
    private val controller = KeepAliveController(scope, sessions) { idles++ }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `it lets go once the last session has ended`() {
        sessions.value = 1
        controller.onStart()
        assertThat(idles).isEqualTo(0)

        sessions.value = 0

        assertThat(idles).isEqualTo(1)
    }

    @Test
    fun `starts while a watcher is waiting do not arm another`() {
        sessions.value = 1
        controller.onStart()
        controller.onStart()
        controller.onStart()

        sessions.value = 0

        assertThat(idles).isEqualTo(1)
    }

    @Test
    fun `a start after the service let go arms a new watcher`() {
        sessions.value = 1
        controller.onStart()
        sessions.value = 0
        assertThat(idles).isEqualTo(1)

        // The next transfer: the listener asks for the service again before the old instance is destroyed.
        sessions.value = 1
        controller.onStart()
        assertThat(idles).isEqualTo(1)
        sessions.value = 0

        assertThat(idles).isEqualTo(2)
    }

    @Test
    fun `a start with no session running lets go at once`() {
        controller.onStart()

        assertThat(idles).isEqualTo(1)
    }
}
