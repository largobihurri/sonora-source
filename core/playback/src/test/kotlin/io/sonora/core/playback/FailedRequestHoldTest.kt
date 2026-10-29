package io.sonora.core.playback

import android.os.Bundle
import androidx.media3.session.MediaSession.ControllerInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which commands a failed request holds back: only its own controller's, until its play, a new request or the timeout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FailedRequestHoldTest {

    private var now = 0L
    private val hold = FailedRequestHold { now }
    private val car = controller("com.example.car", uid = 1)
    private val app = controller("io.sonora.player", uid = 2)

    private fun controller(name: String, uid: Int) =
        ControllerInfo.createTestOnlyControllerInfo(name, 0, uid, 0, 0, false, Bundle.EMPTY, false)

    @Test
    fun `the failing controller's prepare and play are held, then the hold ends with the play`() {
        hold.failed(car)

        assertThat(hold.holds(car, ends = false)).isTrue()
        assertThat(hold.holds(car, ends = true)).isTrue()
        assertThat(hold.holds(car, ends = true)).isFalse()
    }

    @Test
    fun `another controller is never held`() {
        hold.failed(car)

        assertThat(hold.holds(app, ends = true)).isFalse()
        assertThat(hold.holds(null, ends = true)).isFalse()
        assertThat(hold.holds(car, ends = true)).isTrue()
    }

    @Test
    fun `a new request or the timeout ends the hold`() {
        hold.failed(car)
        hold.release(car)
        assertThat(hold.holds(car, ends = true)).isFalse()

        hold.failed(car)
        now += FailedRequestHold.HOLD_MS + 1
        assertThat(hold.holds(car, ends = true)).isFalse()
    }
}
