package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class GravityLevelTest {

    private val g = 9.81f

    /** Gravity as the sensor reads it for a phone turned [degrees] clockwise in its own plane, raised [tilt] from flat. */
    private fun reading(degrees: Float, tilt: Float = 90f): FloatArray {
        val inPlane = g * sin(Math.toRadians(tilt.toDouble())).toFloat()
        val normal = g * cos(Math.toRadians(tilt.toDouble())).toFloat()
        val radians = Math.toRadians(degrees.toDouble())
        return floatArrayOf(inPlane * sin(radians).toFloat(), inPlane * cos(radians).toFloat(), normal)
    }

    private fun GravityLevel.read(degrees: Float, tilt: Float = 90f, rotation: Int = 0): Boolean {
        val (x, y, z) = reading(degrees, tilt)
        return onGravity(x, y, z, rotation)
    }

    /** Steps at 60 Hz for [seconds]; the largest angle change of a single frame. */
    private fun GravityLevel.run(seconds: Float, frame: Float = 1f / 60f): Float {
        var largest = 0f
        repeat((seconds / frame).toInt()) {
            val before = angle
            step(frame)
            largest = maxOf(largest, abs(angle - before))
        }
        return largest
    }

    // Screen-plane angle

    @Test
    fun `the angle points the groups' top at the sky, clockwise as the phone turns anticlockwise`() {
        assertThat(screenAngle(0f, g, displayRotation = 0)).isWithin(0.01f).of(0f)
        // Turned anticlockwise, its right edge up: gravity reads +x and the groups turn clockwise to stay level.
        assertThat(screenAngle(g, 0f, displayRotation = 0)).isWithin(0.01f).of(90f)
        assertThat(screenAngle(-g, 0f, displayRotation = 0)).isWithin(0.01f).of(-90f)
        assertThat(abs(screenAngle(0f, -g, displayRotation = 0))).isWithin(0.01f).of(180f)
        assertThat(screenAngle(1f, 1f, displayRotation = 0)).isWithin(0.01f).of(45f)
    }

    @Test
    fun `a turned display takes its own quarter turns off the angle`() {
        // Display at ROTATION_90 is what the phone shows held with its right edge up: already level.
        assertThat(screenAngle(g, 0f, displayRotation = 1)).isWithin(0.01f).of(0f)
        assertThat(screenAngle(-g, 0f, displayRotation = 3)).isWithin(0.01f).of(0f)
        assertThat(abs(screenAngle(0f, g, displayRotation = 2))).isWithin(0.01f).of(180f)
    }

    @Test
    fun `tilt from flat runs from 0 lying flat to 90 held upright`() {
        assertThat(tiltFromFlat(0f, 0f, g)).isWithin(0.01f).of(0f)
        assertThat(tiltFromFlat(0f, g, 0f)).isWithin(0.01f).of(90f)
        val (x, y, z) = reading(30f, tilt = 40f)
        assertThat(tiltFromFlat(x, y, z)).isWithin(0.01f).of(40f)
    }

    // Following gravity

    @Test
    fun `the first reading places the groups at once, without a spin in`() {
        val level = GravityLevel()

        assertThat(level.read(90f)).isTrue()

        assertThat(level.angle).isWithin(0.01f).of(90f)
        assertThat(level.settled).isTrue()
    }

    @Test
    fun `opened lying flat the groups stay upright as shown, and raising the phone turns them there smoothly`() {
        val level = GravityLevel()
        // The first reading is held: nothing moves, the groups stay upright (as they were drawn on opening).
        assertThat(level.read(90f, tilt = 5f)).isFalse()
        assertThat(level.angle).isEqualTo(0f)
        assertThat(level.settled).isTrue()

        // Raised sideways: not placed at once (a jump of the groups already on screen) but turned there.
        assertThat(level.read(60f)).isTrue()
        assertThat(level.angle).isEqualTo(0f)
        assertThat(level.settled).isFalse()
        val largest = level.run(2f)
        assertThat(largest).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND / 60f + 0.001f)
        assertThat(level.angle).isWithin(0.01f).of(60f)
    }

    @Test
    fun `a turn is followed smoothly and settles exactly on the level angle`() {
        val level = GravityLevel()
        level.read(0f)

        assertThat(level.read(90f)).isTrue()
        assertThat(level.settled).isFalse()
        level.run(3f)

        assertThat(level.angle).isEqualTo(90f)
        assertThat(level.settled).isTrue()
        assertThat(level.step(1f / 60f)).isFalse()
    }

    @Test
    fun `the follow is critically damped, it never overshoots the level angle`() {
        val level = GravityLevel()
        level.read(0f)
        level.read(60f)

        var highest = 0f
        repeat(240) {
            level.step(1f / 60f)
            highest = maxOf(highest, level.angle)
        }

        assertThat(highest).isAtMost(60f)
    }

    @Test
    fun `held upright the angular speed never passes the maximum`() {
        val level = GravityLevel()
        level.read(0f)
        level.read(179f)

        val largest = level.run(2f)

        assertThat(largest).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND / 60f + 0.001f)
        // and a long frame moves no further than the speed allows over it
        val other = GravityLevel()
        other.read(0f)
        other.read(170f)
        other.step(0.05f)
        assertThat(abs(other.angle)).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND * 0.05f + 0.001f)
    }

    @Test
    fun `a phone barely raised from flat turns slower than one held upright`() {
        val upright = GravityLevel().apply { read(0f, tilt = 90f); read(150f, tilt = 90f) }
        val low = GravityLevel().apply { read(0f, tilt = 30f); read(150f, tilt = 30f) }

        val uprightFastest = upright.run(0.5f)
        val lowFastest = low.run(0.5f)

        assertThat(lowFastest).isLessThan(uprightFastest)
        assertThat(lowFastest).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND * GravityLevel.responsiveness(30f) / 60f + 0.001f)
    }

    @Test
    fun `turning past upside down goes the short way, continuing beyond 180 without a jump`() {
        val level = GravityLevel()
        level.read(170f)

        level.read(-170f) // 190 the short way: 20 degrees on, not 340 back
        level.run(2f)

        assertThat(level.angle).isWithin(0.01f).of(190f)
    }

    @Test
    fun `turning round and round keeps counting, so the groups follow all the way`() {
        val level = GravityLevel()
        level.read(0f)
        var degrees = 0f
        repeat(16) { // two full turns, an eighth at a time
            degrees += 45f
            level.read(degrees)
            level.run(1.5f)
        }

        assertThat(level.angle).isWithin(0.01f).of(720f)
    }

    @Test
    fun `a small tremor stays inside the dead band and wakes nothing`() {
        val level = GravityLevel()
        level.read(10f)

        for (wobble in listOf(10.5f, 9.4f, 11f, 9.2f, 10.8f)) assertThat(level.read(wobble)).isFalse()

        assertThat(level.settled).isTrue()
        assertThat(level.angle).isWithin(0.01f).of(10f)
    }

    // Magnetic detents

    @Test
    fun `practically sideways the groups are pulled exactly onto the quarter turn and held there`() {
        val level = GravityLevel()
        level.read(0f)

        assertThat(level.read(85f)).isTrue()
        level.run(3f)

        assertThat(level.angle).isEqualTo(90f)
        assertThat(level.detent).isEqualTo(1)
        assertThat(level.settled).isTrue()
    }

    @Test
    fun `practically upright or upside down the groups snap level too, from either side`() {
        for ((degrees, level) in listOf(5f to 0f, -5f to 0f, 183f to 180f, -94f to -90f, 268f to -90f)) {
            val snapped = GravityLevel()
            snapped.read(degrees)
            snapped.run(3f)
            assertThat(snapped.angle).isWithin(0.001f).of(level)
        }
        assertThat(GravityLevel().apply { read(5f) }.detent).isEqualTo(0)
        assertThat(GravityLevel().apply { read(-178f) }.detent).isEqualTo(2)
        assertThat(GravityLevel().apply { read(-88f) }.detent).isEqualTo(3)
    }

    @Test
    fun `away from the detents the groups follow gravity as before`() {
        val level = GravityLevel()
        level.read(0f)

        level.read(80f) // 10 degrees from sideways: outside the capture zone
        level.run(3f)

        assertThat(level.angle).isWithin(0.01f).of(80f)
        assertThat(level.detent).isNull()
    }

    @Test
    fun `a detent holds exactly within its hold zone, eases out through its release zone and catches again only inside the capture zone`() {
        val level = GravityLevel()
        level.read(85f) // caught at 90
        level.run(3f)

        // Inside the hold zone: held exactly, nothing wakes.
        assertThat(level.read(90f - GravityLevel.SIDEWAYS_HOLD_DEGREES + 0.5f)).isFalse()
        assertThat(level.read(90f + GravityLevel.SIDEWAYS_HOLD_DEGREES - 0.5f)).isFalse()
        assertThat(level.angle).isEqualTo(90f)

        // Through the release zone: still held, the groups lag behind the hand, eased toward it.
        val easing = 90f - (GravityLevel.SIDEWAYS_HOLD_DEGREES + GravityLevel.SIDEWAYS_RELEASE_DEGREES) / 2f
        level.read(easing)
        level.run(3f)
        assertThat(level.detent).isEqualTo(1)
        assertThat(level.angle).isGreaterThan(easing)
        assertThat(level.angle).isLessThan(90f)

        // Past the release zone: follows gravity again.
        level.read(90f - GravityLevel.SIDEWAYS_RELEASE_DEGREES - 0.5f)
        assertThat(level.detent).isNull()
        level.run(3f)
        assertThat(level.angle).isWithin(0.01f).of(90f - GravityLevel.SIDEWAYS_RELEASE_DEGREES - 0.5f)

        // Back inside the release zone but not the capture zone: still free.
        level.read(90f - GravityLevel.CAPTURE_DEGREES - 1f)
        assertThat(level.detent).isNull()
        // Inside the capture zone: caught.
        assertThat(level.read(90f - GravityLevel.CAPTURE_DEGREES + 0.5f)).isTrue()
        assertThat(level.detent).isEqualTo(1)
    }

    @Test
    fun `the sideways detents hold longer than the upright ones`() {
        assertThat(GravityLevel.SIDEWAYS_HOLD_DEGREES).isGreaterThan(GravityLevel.UPRIGHT_HOLD_DEGREES)
        assertThat(GravityLevel.SIDEWAYS_RELEASE_DEGREES).isGreaterThan(GravityLevel.UPRIGHT_RELEASE_DEGREES)
        assertThat(GravityLevel.UPRIGHT_HOLD_DEGREES).isAtLeast(GravityLevel.CAPTURE_DEGREES)

        // 13 degrees off: sideways still held exactly, upright already easing away.
        val sideways = GravityLevel().apply { read(88f); run(3f); read(77f); run(3f) }
        assertThat(sideways.angle).isEqualTo(90f)
        val upright = GravityLevel().apply { read(2f); run(3f); read(13f); run(3f) }
        assertThat(upright.detent).isEqualTo(0)
        assertThat(upright.angle).isGreaterThan(0f)
        // Upside down holds as upright does, the other sideways as sideways.
        assertThat(GravityLevel().apply { read(178f); run(3f); read(167f); run(3f) }.angle).isLessThan(180f)
        assertThat(GravityLevel().apply { read(-88f); run(3f); read(-77f); run(3f) }.angle).isEqualTo(-90f)
    }

    /** The groups' angle each 60 Hz frame while the hand turns from [from] to [to] at [degreesPerSecond], a reading a frame. */
    private fun GravityLevel.sweep(from: Float, to: Float, degreesPerSecond: Float): List<Float> {
        val frames = (abs(to - from) / degreesPerSecond * 60f).toInt()
        val angles = ArrayList<Float>()
        for (i in 1..frames) {
            read(from + (to - from) * i / frames)
            step(1f / 60f)
            angles += angle
        }
        return angles
    }

    /** The largest change of angular speed (degrees per second) from one 60 Hz frame to the next. */
    private fun largestSpeedChange(angles: List<Float>): Float {
        val speeds = angles.zipWithNext { a, b -> (b - a) * 60f }
        return speeds.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
    }

    @Test
    fun `leaving a detent slowly is smooth, with no jump in angle or angular speed at the release edge`() {
        for ((quarter, release) in listOf(90f to GravityLevel.SIDEWAYS_RELEASE_DEGREES, 0f to GravityLevel.UPRIGHT_RELEASE_DEGREES)) {
            val level = GravityLevel()
            level.read(quarter)
            level.run(1f)

            val angles = level.sweep(quarter, quarter - release - 15f, degreesPerSecond = 15f)

            assertWithMessage("from $quarter").that(level.detent).isNull()
            level.run(2f)
            assertWithMessage("follows again from $quarter").that(level.angle).isWithin(GravityLevel.DEAD_BAND_DEGREES).of(quarter - release - 15f)
            val steps = angles.zipWithNext { a, b -> abs(b - a) }
            // Never faster than about three times the hand (15 degrees a second): a quarter of a degree a frame.
            assertWithMessage("angle from $quarter").that(steps.max()).isAtMost(3f * 15f / 60f)
            // The speed changes gradually: no kick when the detent lets go (the old 12 degree release kicked 17 deg/s).
            assertWithMessage("speed from $quarter").that(largestSpeedChange(angles)).isAtMost(3f)
            // And it never turns back toward the detent on the way out.
            assertWithMessage("monotonic from $quarter").that(angles.zipWithNext { a, b -> b - a }.max()).isAtMost(0.0001f)
        }
    }

    @Test
    fun `turning slowly onto a detent glides onto it, its angle and speed continuous`() {
        val level = GravityLevel()
        level.read(60f)
        level.run(1f)

        val angles = level.sweep(60f, 90f, degreesPerSecond = 15f)

        assertThat(level.detent).isEqualTo(1)
        level.run(2f)
        assertThat(level.angle).isEqualTo(90f)
        // The snap is the critically damped, speed-capped pull: no jump in angle, the speed builds up over frames.
        assertThat(angles.zipWithNext { a, b -> abs(b - a) }.max()).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND / 60f)
        assertThat(largestSpeedChange(angles)).isAtMost(100f * GravityLevel.CAPTURE_DEGREES / 60f)
        assertThat(angles.zipWithNext { a, b -> b - a }.min()).isAtLeast(-0.0001f)
    }

    @Test
    fun `the pull onto a detent is as smooth and speed-limited as any turn`() {
        val level = GravityLevel()
        level.read(80f)
        level.run(3f)

        level.read(83f) // caught: the aim jumps to 90, the groups glide there
        val largest = level.run(2f)

        assertThat(largest).isAtMost(GravityLevel.MAX_DEGREES_PER_SECOND / 60f + 0.001f)
        assertThat(largest).isGreaterThan(0f)
        assertThat(level.angle).isEqualTo(90f)
    }

    @Test
    fun `a detent after whole turns keeps the angle counting continuously`() {
        val level = GravityLevel()
        level.read(0f)
        var degrees = 0f
        repeat(9) { // round once and a quarter, an eighth at a time
            degrees += 50f
            level.read(degrees)
            level.run(1.5f)
        }
        level.read(452f) // practically sideways again, one turn on

        level.run(3f)

        assertThat(level.angle).isWithin(0.001f).of(450f)
        assertThat(level.detent).isEqualTo(1)
    }

    @Test
    fun `lying flat keeps the detent it had`() {
        val level = GravityLevel()
        level.read(88f)

        assertThat(level.read(0f, tilt = 10f)).isFalse()

        assertThat(level.detent).isEqualTo(1)
    }

    // Lying flat

    @Test
    fun `lying flat holds the angle still, whatever the in-plane gravity says`() {
        val level = GravityLevel()
        level.read(30f)

        for (degrees in listOf(-170f, 90f, 0f, -45f, 120f)) {
            assertThat(level.read(degrees, tilt = 10f)).isFalse()
        }
        level.run(1f)

        assertThat(level.angle).isWithin(0.01f).of(30f)
        assertThat(level.settled).isTrue()
    }

    @Test
    fun `tipping a phone lying flat quickly from one edge to the other never spins the groups`() {
        val level = GravityLevel()
        val (x, y, z) = floatArrayOf(0f, 0f, g)
        level.onGravity(x, y, z, 0)

        // A sharp rock between opposite edges, 15 degrees each way, 20 times a second.
        repeat(40) { i ->
            val woken = level.read(if (i % 2 == 0) 90f else -90f, tilt = 15f)
            assertThat(woken).isFalse()
            level.run(0.05f)
        }

        assertThat(level.angle).isEqualTo(0f)
        assertThat(level.settled).isTrue()
    }

    @Test
    fun `responsiveness fades in with the tilt, and coming back from flat needs a little more tilt`() {
        assertThat(GravityLevel.responsiveness(GravityLevel.HOLD_BELOW_DEGREES)).isEqualTo(0f)
        assertThat(GravityLevel.responsiveness(GravityLevel.FULL_RESPONSE_DEGREES)).isEqualTo(1f)
        assertThat(GravityLevel.responsiveness(27.5f)).isWithin(0.01f).of(0.5f)

        val level = GravityLevel()
        level.read(0f, tilt = 10f) // flat: held
        assertThat(level.read(90f, tilt = 22f)).isFalse() // above the hold line, below the resume line: still held
        assertThat(level.read(90f, tilt = 26f)).isTrue() // past the resume line: follows
        assertThat(level.read(60f, tilt = 22f)).isTrue() // following, it only lets go below the hold line
        assertThat(level.read(0f, tilt = 18f)).isFalse()
    }

    @Test
    fun `a turn already under way when the phone is laid down still comes to rest`() {
        val level = GravityLevel()
        level.read(0f)
        level.read(120f)
        level.run(0.2f)

        level.read(-60f, tilt = 5f) // laid flat mid-turn: the aim stays, the motion finishes
        level.run(6f)

        assertThat(level.angle).isEqualTo(120f)
        assertThat(level.settled).isTrue()
    }

    @Test
    fun `no gravity at all, as from a sensor in free fall, changes nothing`() {
        val level = GravityLevel()
        level.read(45f)

        assertThat(level.onGravity(0f, 0f, 0f, 0)).isFalse()
        assertThat(level.angle).isWithin(0.01f).of(45f)
    }

    // Accelerometer fallback

    @Test
    fun `the accelerometer low-pass passes the first reading and eases towards a new one`() {
        val filter = GravityLowPass(timeConstantSeconds = 0.15f)

        assertThat(filter.filter(0f, g, 0f, 0L).toList()).containsExactly(0f, g, 0f).inOrder()
        val eased = filter.filter(g, 0f, 0f, 20_000_000L) // 20 ms later
        assertThat(eased[0]).isGreaterThan(0f)
        assertThat(eased[0]).isLessThan(g / 2f)
        assertThat(eased[1]).isGreaterThan(g / 2f)

        var last = eased
        for (i in 2..100) last = filter.filter(g, 0f, 0f, i * 20_000_000L)
        assertThat(last[0]).isWithin(0.01f).of(g)
        assertThat(last[1]).isWithin(0.01f).of(0f)
    }

    // Smoothing every reading (T63.16)

    /**
     * Feeds [hand] (the hand's angle at each second) through a [GravitySmoother] into the level, one reading every 20 ms
     * (the game rate) for [seconds], stepping the level at the same rate; the times (s) at which a reading woke the level.
     */
    private fun GravityLevel.held(seconds: Float, tilt: Float = 60f, hand: (Float) -> Float): List<Float> {
        val smoother = GravitySmoother()
        val woken = ArrayList<Float>()
        val dt = 0.02f
        for (i in 0 until (seconds / dt).toInt()) {
            val t = i * dt
            val (x, y, z) = reading(hand(t), tilt)
            val smooth = smoother.filter(x, y, z, (t * 1e9).toLong())
            if (onGravity(smooth[0], smooth[1], smooth[2], 0)) woken += t
            step(dt)
        }
        return woken
    }

    @Test
    fun `held still at an angle with a trembling hand, the smoothed readings settle the aim and wake nothing`() {
        for (seed in 1..5) {
            val random = kotlin.random.Random(seed)
            val level = GravityLevel()
            // A hand at 30 degrees: a 9 Hz tremor and a slower 3.3 Hz sway, plus the sensor's own jitter, up to +-3.3.
            val woken = level.held(seconds = 10f) { t ->
                30f + 1.5f * sin(2f * Math.PI.toFloat() * 9f * t) + 0.8f * sin(2f * Math.PI.toFloat() * 3.3f * t + 1f) +
                    random.nextFloat() * 2f - 1f
            }

            // The first reading, taken as it comes, places the groups up to a few degrees off the hand's mean; after the
            // smoothing has settled (a second) at most one glide brings them onto it, and from then on nothing wakes
            // (unsmoothed, the same hand wakes the level about twenty times a second).
            val late = woken.filter { it >= 1f }
            assertWithMessage("woken after settling, seed $seed").that(late.size).isAtMost(1)
            assertWithMessage("woken in the last five seconds, seed $seed").that(late.filter { it >= 5f }).isEmpty()
            assertThat(level.settled).isTrue()
            assertThat(level.angle).isWithin(2f).of(30f)
        }
    }

    @Test
    fun `a real turn through the smoothing is still followed promptly`() {
        // The hand turns from 0 to 60 degrees at 180 degrees a second (a third of a second), then holds.
        val turn = { t: Float -> (t * 180f).coerceAtMost(60f) }
        val dt = 0.02f

        /** When the groups first come within the dead band of where the hand stopped, readings smoothed or not. */
        fun arrival(smooth: Boolean): Float {
            val level = GravityLevel()
            val smoother = GravitySmoother()
            for (i in 0 until 150) {
                val (x, y, z) = reading(turn(i * dt))
                val v = if (smooth) smoother.filter(x, y, z, i * 20_000_000L) else floatArrayOf(x, y, z)
                level.onGravity(v[0], v[1], v[2], 0)
                level.step(dt)
                if (abs(level.angle - 60f) < GravityLevel.DEAD_BAND_DEGREES) return i * dt
            }
            return Float.MAX_VALUE
        }

        // The groups arrive no more than a tenth of a second later than they would with the readings as they come.
        val direct = arrival(smooth = false)
        assertThat(direct).isLessThan(1f)
        assertThat(arrival(smooth = true)).isAtMost(direct + 0.1f)
    }

    @Test
    fun `the smoothing passes the first reading and keeps a steady one as it is`() {
        val smoother = GravitySmoother()

        assertThat(smoother.filter(1f, g, 0.5f, 0L).toList()).containsExactly(1f, g, 0.5f).inOrder()
        var last = floatArrayOf()
        for (i in 1..50) last = smoother.filter(1f, g, 0.5f, i * 20_000_000L)
        assertThat(last.toList()).containsExactly(1f, g, 0.5f).inOrder()
    }

    /**
     * A second held upright (one reading every 20 ms), then [between] fed to the smoother with the last reading's
     * timestamp, then a turn to 30 degrees at 180 degrees a second; the smoothed in-plane angles during the turn.
     */
    private fun smoothedTurn(between: (GravitySmoother, Long) -> Unit = { _, _ -> }): List<Float> {
        val smoother = GravitySmoother()
        var nanos = 0L
        for (i in 0..50) {
            nanos = i * 20_000_000L
            val (x, y, z) = reading(0f)
            smoother.filter(x, y, z, nanos)
        }
        between(smoother, nanos)
        return (1..10).map { i ->
            val (x, y, z) = reading((i * 0.02f * 180f).coerceAtMost(30f))
            val v = smoother.filter(x, y, z, nanos + i * 20_000_000L)
            Math.toDegrees(atan2(v[0], v[1]).toDouble()).toFloat()
        }
    }

    @Test
    fun `a reading with the same timestamp as the last one does not open the smoothing`() {
        val reference = smoothedTurn()
        val withRepeat = smoothedTurn { smoother, nanos ->
            val (x, y, z) = reading(30f)
            smoother.filter(x, y, z, nanos)
        }

        // A repeated timestamp is no time at all: the turn that follows is smoothed as if the reading never came.
        for (i in reference.indices) assertThat(withRepeat[i]).isWithin(0.05f).of(reference[i])
    }

    @Test
    fun `a reading timestamped before the last one does not open the smoothing`() {
        val reference = smoothedTurn()
        val withBackward = smoothedTurn { smoother, nanos ->
            val (x, y, z) = reading(30f)
            smoother.filter(x, y, z, nanos - 1_000_000L)
        }

        for (i in reference.indices) assertThat(withBackward[i]).isWithin(0.05f).of(reference[i])
    }

    @Test
    fun `a reading delivered late, long after its time, leaves the clock at the latest reading`() {
        val reference = smoothedTurn()
        // Half a second old: were the clock moved back to it, the next reading would count that half second as its own
        // interval and the smoothing would snap to it.
        val withLate = smoothedTurn { smoother, nanos ->
            val (x, y, z) = reading(30f)
            smoother.filter(x, y, z, nanos - 500_000_000L)
        }

        for (i in reference.indices) assertThat(withLate[i]).isWithin(0.05f).of(reference[i])
    }

    @Test
    fun `a smoothed vector of practically no length is not taken for a fast turn`() {
        val smoother = GravitySmoother()
        smoother.filter(0f, 1e-5f, 0f, 0L)

        // Gravity coming back along the same direction is no turn: the smoothing stays as slow as when held still.
        val first = smoother.filter(0f, g, 0f, 20_000_000L)
        assertThat(first[1]).isLessThan(1f)
        assertThat(first[0]).isEqualTo(0f)
        assertThat(first[2]).isEqualTo(0f)
    }

    // Fitting inside the group's area

    @Test
    fun `upright the group keeps its size, turned it shrinks just enough to stay inside its area`() {
        val area = Rect(0f, 0f, 1000f, 1200f)
        val content = Rect(100f, 100f, 900f, 1100f) // 800 x 1000, centred

        assertThat(levelFitScale(content, area, 0f)).isEqualTo(1f)
        // A quarter turn: 1000 x 800 must fit 1000 wide.
        assertThat(levelFitScale(content, area, 90f)).isWithin(0.001f).of(1f)
        // Half way: the outline is (800 + 1000) * 0.707 square; it must fit 1000 wide.
        assertThat(levelFitScale(content, area, 45f)).isWithin(0.001f).of(1000f / (1800f * 0.70710677f))
        assertThat(levelFitScale(content, area, 225f)).isWithin(0.001f).of(levelFitScale(content, area, 45f))
    }

    @Test
    fun `an off-centre group fits by its nearest edges, never growing past its own size`() {
        val area = Rect(0f, 0f, 1000f, 1000f)
        val content = Rect(300f, 0f, 700f, 400f) // 400 square touching the top; centre at (500, 200)

        // Upright it already fits. Turned 45, its outline is 566 square; only 200 above the centre.
        assertThat(levelFitScale(content, area, 0f)).isEqualTo(1f)
        assertThat(levelFitScale(content, area, 45f)).isWithin(0.001f).of(400f / 565.6854f)
        // A tiny group in a large area stays its size.
        assertThat(levelFitScale(Rect(490f, 490f, 510f, 510f), area, 45f)).isEqualTo(1f)
    }

    // The artwork's floor (T63.14)

    // A 1080 x 2400 phone: the padded frame (status bar 110 px, navigation 60 px), the artwork's room between the handle
    // and the controls (little taller than the artwork, as on the Realme), and the 924 px square artwork centred in it.
    private val frame = Rect(0f, 110f, 1080f, 2340f)
    private val room = Rect(0f, 168f, 1080f, 1168f)
    private val artwork = Rect(78f, 206f, 1002f, 1130f)

    @Test
    fun `the artwork shrinks to no less than 0_85 as it turns, where the phone's frame has room for it`() {
        // Upright and at a quarter turn the square fits its room as before.
        assertThat(levelScale(artwork, room, frame, 0f)).isEqualTo(1f)
        assertThat(levelScale(artwork, room, frame, 90f)).isWithin(0.0001f).of(1f)
        // Turned a little, the room still decides while it allows at least the floor.
        assertThat(levelScale(artwork, room, frame, 3f)).isEqualTo(levelFitScale(artwork, room, 3f))
        assertThat(levelFitScale(artwork, room, 3f)).isAtLeast(LEVEL_MIN_SCALE)
        // Turned 20 degrees the room alone would take it below the floor; the frame has room for 0.85.
        assertThat(levelFitScale(artwork, room, 20f)).isLessThan(LEVEL_MIN_SCALE)
        assertThat(levelScale(artwork, room, frame, 20f)).isEqualTo(LEVEL_MIN_SCALE)
        // At 45 the window's width caps it: 1080 px for the 924 px square's 1307 px diagonal (the room alone: 0.77).
        assertThat(levelScale(artwork, room, frame, 45f)).isWithin(0.001f).of(1080f / (924f * 1.4142135f))
        assertThat(levelScale(artwork, room, frame, 45f)).isGreaterThan(levelFitScale(artwork, room, 45f))
    }

    @Test
    fun `the floor is capped by the frame, and with no frame known the room alone decides`() {
        // Turned 45 the phone's width allows less than the floor, and that is what the artwork gets; a frame with little
        // room above the artwork caps it by its top instead.
        assertThat(levelScale(artwork, room, frame, 45f)).isWithin(0.0001f).of(levelFitScale(artwork, frame, 45f))
        assertThat(levelScale(artwork, room, frame, 45f)).isLessThan(LEVEL_MIN_SCALE)
        val low = Rect(0f, 140f, 1080f, 2340f)
        assertThat(levelScale(artwork, room, low, 30f)).isWithin(0.0001f).of(levelFitScale(artwork, low, 30f))
        assertThat(levelFitScale(artwork, low, 30f)).isLessThan(LEVEL_MIN_SCALE)
        // Never smaller than the room alone allows.
        assertThat(levelScale(artwork, room, room, 45f)).isEqualTo(levelFitScale(artwork, room, 45f))
        assertThat(levelScale(artwork, room, Rect.Zero, 45f)).isEqualTo(levelFitScale(artwork, room, 45f))
    }

    @Test
    fun `the turned artwork never leaves the phone's frame and never shrinks below the room's fit, all the way round`() {
        // Frames always hold the room: the phone's, one with little room above, one with little below.
        for (box in listOf(frame, Rect(0f, 140f, 1080f, 2340f), Rect(0f, 110f, 1080f, 1200f))) {
            var degrees = 0f
            while (degrees < 360f) {
                val scale = levelScale(artwork, room, box, degrees)
                val radians = Math.toRadians(degrees.toDouble())
                val c = abs(cos(radians)).toFloat()
                val s = abs(sin(radians)).toFloat()
                val half = (artwork.width * c + artwork.height * s) * scale / 2f // a square: the same both ways
                val where = "at $degrees in $box"
                assertWithMessage(where).that(artwork.center.x - half).isAtLeast(box.left - 0.01f)
                assertWithMessage(where).that(artwork.center.x + half).isAtMost(box.right + 0.01f)
                assertWithMessage(where).that(artwork.center.y - half).isAtLeast(box.top - 0.01f)
                assertWithMessage(where).that(artwork.center.y + half).isAtMost(box.bottom + 0.01f)
                assertWithMessage(where).that(scale).isAtLeast(levelFitScale(artwork, room, degrees))
                assertWithMessage(where).that(scale).isAtMost(1f)
                degrees += 2.5f
            }
        }
    }

    @Test
    fun `the turned and scaled outline never leaves the area, all the way round`() {
        val area = Rect(0f, 120f, 1080f, 1300f)
        val content = Rect(74f, 130f, 1006f, 1250f)
        var degrees = 0f
        while (degrees < 360f) {
            val scale = levelFitScale(content, area, degrees)
            val radians = Math.toRadians(degrees.toDouble())
            val c = abs(cos(radians)).toFloat()
            val s = abs(sin(radians)).toFloat()
            val halfWidth = (content.width * c + content.height * s) * scale / 2f
            val halfHeight = (content.width * s + content.height * c) * scale / 2f
            assertThat(content.center.x - halfWidth).isAtLeast(area.left - 0.01f)
            assertThat(content.center.x + halfWidth).isAtMost(area.right + 0.01f)
            assertThat(content.center.y - halfHeight).isAtLeast(area.top - 0.01f)
            assertThat(content.center.y + halfHeight).isAtMost(area.bottom + 0.01f)
            degrees += 7.5f
        }
    }
}
