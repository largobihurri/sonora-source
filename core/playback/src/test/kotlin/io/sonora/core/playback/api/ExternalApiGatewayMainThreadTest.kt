package io.sonora.core.playback.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError
import io.sonora.core.api.ApiQueryResult
import io.sonora.core.api.ApiResult
import io.sonora.core.api.Page
import io.sonora.core.api.TrackRow
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provider's work runs on the main thread. A caller that is already on it (Sonora's own uid, or a provider call
 * made in-process) would wait for work that cannot start, so it must be answered at once instead of holding the
 * main thread for the whole call budget. Robolectric's test thread is the main thread, with a paused looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalApiGatewayMainThreadTest {

    private val ran = AtomicBoolean(false)

    private fun gateway() = ExternalApiGateway(
        switchOn = { true },
        playbackConnected = MutableStateFlow(true),
        runCall = { _, _ -> ran.set(true); ApiResult.Ok() },
        runQuery = { _, _ -> ran.set(true); ApiQueryResult.Tracks(Page(emptyList<TrackRow>(), 0, 50, 0)) },
        workDispatcher = Dispatchers.Main,
        limits = GatewayLimits(callBudgetMs = 1_500, connectWaitMs = 200, switchBudgetMs = 200),
    )

    @Test
    fun `a call made on the main thread fails at once instead of holding it for the budget`() {
        val started = System.nanoTime()

        val result = gateway().call("play", emptyMap())

        assertThat(result).isEqualTo(ApiResult.Failed(ApiError.Failed))
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(500)
        assertThat(ran.get()).isFalse()
    }

    @Test
    fun `a query made on the main thread fails at once instead of holding it for the budget`() {
        val started = System.nanoTime()

        val result = gateway().query(listOf("tracks"), emptyMap())

        assertThat(result).isEqualTo(ApiQueryResult.Failed(ApiError.Failed))
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(500)
        assertThat(ran.get()).isFalse()
    }
}
