package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gravity mode's level (T63): the angle, in degrees clockwise on the screen, by which Now Playing's two groups turn
 * about their own centres so that their top points at the sky as the phone tilts. Pure: fed sensor readings
 * ([onGravity]) and frame times ([step]); the screen reads [angle] inside a layer block.
 *
 * Limits:
 * - Lying flat, gravity barely leans into the screen's plane and its direction there is noise: below
 *   [HOLD_BELOW_DEGREES] of tilt from flat the aim holds. It resumes only past [RESUME_ABOVE_DEGREES] (hysteresis), so a
 *   phone on a table rocked from edge to edge never spins the groups.
 * - The fastest the groups turn is [MAX_DEGREES_PER_SECOND] held upright, scaled down by [responsiveness] as the phone
 *   nears flat (never below [MIN_SPEED_FRACTION], so a turn under way when the phone is laid down still ends).
 * - The groups follow the aim critically damped (no whip, no overshoot).
 * - A new reading within [DEAD_BAND_DEGREES] of the aim is ignored, and a motion that comes within [SETTLE_DEGREES] at
 *   under [SETTLE_DEGREES_PER_SECOND] snaps to the aim and [settled]: a still hand keeps no frames running.
 * - The aim moves the short way round and keeps counting past 360, so the groups turn continuously, never jumping
 *   across +-180.
 * - Magnetic detents (T63.5): practically upright, sideways or upside down (within [CAPTURE_DEGREES] of a quarter turn)
 *   the aim is that quarter turn exactly, so the groups glide onto it (as smoothly and speed-limited as any turn) and
 *   rest there. [detent] says which quarter turn holds them.
 * - Leaving a detent (T63.10): the aim stays exactly on the quarter turn within its hold zone, then eases out through
 *   its release zone ([magnetAim]) to meet the reading, at the same rate, at the zone's edge, where the detent lets go:
 *   no jump in the aim, so none in the groups' angle or speed. Sideways the magnet is stronger (it holds to
 *   [SIDEWAYS_HOLD_DEGREES] and lets go at [SIDEWAYS_RELEASE_DEGREES]) than upright or upside down
 *   ([UPRIGHT_HOLD_DEGREES], [UPRIGHT_RELEASE_DEGREES]). Caught again only inside the capture zone (hysteresis), so a
 *   hand at the edge never makes them jitter.
 */
internal class GravityLevel {

    /** The groups' turn, clockwise in degrees; continuous (it may pass 360 or -360 as the phone goes round). */
    var angle: Float = 0f
        private set

    /** Whether the groups are at rest on the aim: no frame needs drawing until a reading moves the aim. */
    var settled: Boolean = true
        private set

    /** The quarter turn (0 upright, 1 a quarter clockwise, 2 upside down, 3) a detent holds the groups on; null while free. */
    var detent: Int? = null
        private set

    private var aim = 0f
    private var velocity = 0f
    private var started = false
    private var held = false
    private var response = 1f

    /**
     * A gravity reading ([x], [y], [z] in the sensor's axes, m/s2, pointing up as the sensor reports it) with the display
     * at [displayRotation] quarter turns (Surface.ROTATION_*). Returns whether the groups must start (or keep) moving:
     * the caller wakes its frame loop then. The first reading places them at once (no spin in on opening), unless it is
     * held (the phone lies flat): then the groups stay upright, as they are already drawn, and the first reading that
     * lets them move turns them there like any other, never with a jump (R3-first-reading-held).
     */
    fun onGravity(x: Float, y: Float, z: Float, displayRotation: Int): Boolean {
        val total = sqrt(x * x + y * y + z * z)
        if (total < MIN_GRAVITY) return false
        val tilt = tiltFromFlat(x, y, z)
        held = if (held) tilt < RESUME_ABOVE_DEGREES else tilt < HOLD_BELOW_DEGREES
        response = responsiveness(tilt)
        if (held) {
            started = true
            return false
        }
        val measured = screenAngle(x, y, displayRotation)
        val caught = detentFor(measured)
        val changed = caught != detent
        detent = caught
        val target = if (caught == null) measured else magnetAim(caught, measured)
        if (!started) {
            started = true
            aim = target
            angle = target
            velocity = 0f
            settled = true
            return true
        }
        val delta = wrapDegrees(target - aim)
        // The dead band spares a trembling hand's frames while following; never when a detent catches, lets go or
        // pulls the groups exactly onto its quarter turn.
        val onQuarter = caught != null && target == wrapDegrees(caught * 90f)
        if (delta == 0f || (!changed && !onQuarter && abs(delta) < DEAD_BAND_DEGREES)) return false
        aim += delta
        rebase()
        settled = false
        return true
    }

    /** Advances the motion by [seconds]; returns whether it is still moving (another frame is needed). */
    fun step(seconds: Float): Boolean {
        if (settled) return false
        val h = seconds.coerceIn(0f, MAX_STEP_SECONDS)
        val x0 = angle - aim
        val v0 = velocity
        // Exact critically damped spring over h: stable for any frame length.
        val decay = exp(-STIFFNESS * h)
        var x = (x0 + (v0 + STIFFNESS * x0) * h) * decay
        var v = (v0 - STIFFNESS * (v0 + STIFFNESS * x0) * h) * decay
        val limit = MAX_DEGREES_PER_SECOND * maxOf(response, MIN_SPEED_FRACTION)
        x = x0 + (x - x0).coerceIn(-limit * h, limit * h)
        v = v.coerceIn(-limit, limit)
        if (x0 != 0f && x * x0 < 0f) { // the speed cap's lag must not carry it past the aim
            x = 0f
            v = 0f
        }
        angle = aim + x
        velocity = v
        if (abs(x) < SETTLE_DEGREES && abs(v) < SETTLE_DEGREES_PER_SECOND) {
            angle = aim
            velocity = 0f
            settled = true
        }
        return !settled
    }

    /** The quarter turn that holds a [measured] angle: the current one until past its release zone, a new one inside its capture zone. */
    private fun detentFor(measured: Float): Int? {
        val held = detent
        if (held != null && abs(wrapDegrees(measured - held * 90f)) < releaseDegrees(held)) return held
        val quarter = (measured / 90f).roundToInt().mod(4)
        return quarter.takeIf { abs(wrapDegrees(measured - quarter * 90f)) <= CAPTURE_DEGREES }
    }

    /** Keeps the numbers small after many turns; whole turns look the same. */
    private fun rebase() {
        if (abs(aim) < REBASE_DEGREES) return
        val turns = (aim / 360f).roundToInt() * 360f
        aim -= turns
        angle -= turns
    }

    companion object {
        /** Below this tilt from flat the aim holds still. */
        const val HOLD_BELOW_DEGREES = 20f

        /** Held, the aim resumes only past this tilt (hysteresis against a phone rocking around the hold line). */
        const val RESUME_ABOVE_DEGREES = 25f

        /** From this tilt on, the groups turn at full speed. */
        const val FULL_RESPONSE_DEGREES = 35f

        /** Fastest turn, held upright: half a turn a second. */
        const val MAX_DEGREES_PER_SECOND = 180f

        /** Within this of a quarter turn the groups are caught by its detent. */
        const val CAPTURE_DEGREES = 8f

        /**
         * Upright and upside down, a detent holds the groups exactly on its quarter turn within this (at least the capture
         * zone, so a caught reading rests exactly)...
         */
        const val UPRIGHT_HOLD_DEGREES = 10f

        /** ...and lets go at this, the groups easing out between the two (T63.10). */
        const val UPRIGHT_RELEASE_DEGREES = 18f

        /**
         * Sideways the magnet is stronger (Realme feedback, T63.10): it holds exactly within this, a little past the old
         * 12 degree release...
         */
        const val SIDEWAYS_HOLD_DEGREES = 14f

        /**
         * ...and lets go only at this, the groups easing out between the two; half way (22 degrees off) they still hang 9
         * degrees short of the reading.
         */
        const val SIDEWAYS_RELEASE_DEGREES = 30f

        /** Ignored aim changes: a hand's tremor. */
        const val DEAD_BAND_DEGREES = 1.5f

        private const val MIN_SPEED_FRACTION = 0.2f

        /** Spring's natural frequency (1/s): a small turn settles in about half a second. */
        private const val STIFFNESS = 10f
        private const val SETTLE_DEGREES = 0.1f
        private const val SETTLE_DEGREES_PER_SECOND = 2f
        private const val MAX_STEP_SECONDS = 0.1f
        private const val MIN_GRAVITY = 0.5f
        private const val REBASE_DEGREES = 3600f

        /** Where a detent holding quarter turn [quarter] lets go. */
        fun releaseDegrees(quarter: Int): Float = if (quarter % 2 == 0) UPRIGHT_RELEASE_DEGREES else SIDEWAYS_RELEASE_DEGREES

        /** How far from quarter turn [quarter] its detent holds the groups exactly. */
        fun holdDegrees(quarter: Int): Float = if (quarter % 2 == 0) UPRIGHT_HOLD_DEGREES else SIDEWAYS_HOLD_DEGREES

        /** How freely the groups follow at [tilt] from flat: 0 up to the hold line, 1 from [FULL_RESPONSE_DEGREES]. */
        fun responsiveness(tilt: Float): Float =
            ((tilt - HOLD_BELOW_DEGREES) / (FULL_RESPONSE_DEGREES - HOLD_BELOW_DEGREES)).coerceIn(0f, 1f)
    }
}

/**
 * The aim of a detent holding quarter turn [quarter] (0..3) for a [measured] angle inside its release zone (T63.10): the
 * quarter turn exactly within its hold zone, then easing out to meet [measured] at the release edge with the same slope
 * (a cubic in the distance past the hold zone: flat where the hold ends, as steep as the reading where it lets go), so
 * both the aim and its rate run on unbroken into free following. Outside the release zone, [measured] itself.
 */
internal fun magnetAim(quarter: Int, measured: Float): Float {
    val centre = quarter * 90f
    val off = wrapDegrees(measured - centre)
    val distance = abs(off)
    val hold = GravityLevel.holdDegrees(quarter)
    val release = GravityLevel.releaseDegrees(quarter)
    if (distance <= hold) return wrapDegrees(centre)
    if (distance >= release) return measured
    val u = (distance - hold) / (release - hold)
    val k = (release - hold) / release // the reading's slope at the release edge, in u per eased unit
    val eased = release * u * u * ((3f - k) + (k - 2f) * u) // h(0) = h'(0) = 0, h(1) = 1, h'(1) = k
    return wrapDegrees(centre + if (off < 0f) -eased else eased)
}

/**
 * The clockwise turn, in (-180, 180], that points the groups' top at the sky: from the screen-plane part of gravity
 * ([x] right, [y] up in the sensor's natural axes) less the display's own [displayRotation] quarter turns.
 */
internal fun screenAngle(x: Float, y: Float, displayRotation: Int): Float =
    wrapDegrees(Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat() - 90f * displayRotation)

/** The phone's tilt from lying flat, in degrees: 0 face up or down, 90 held upright or sideways. */
internal fun tiltFromFlat(x: Float, y: Float, z: Float): Float {
    val total = sqrt(x * x + y * y + z * z)
    if (total == 0f) return 0f
    return Math.toDegrees(asin((hypot(x, y) / total).coerceIn(0f, 1f).toDouble())).toFloat()
}

/**
 * The largest scale, at most 1, at which [content] turned [degrees] about its own centre stays inside [area]: its turned
 * outline's bounding box against the room from its centre to the area's nearest edges, on each axis.
 */
internal fun levelFitScale(content: Rect, area: Rect, degrees: Float): Float {
    if (content.width <= 0f || content.height <= 0f) return 1f
    val radians = Math.toRadians(degrees.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    val halfWidth = (content.width * c + content.height * s) / 2f
    val halfHeight = (content.width * s + content.height * c) / 2f
    val center = content.center
    val roomX = minOf(center.x - area.left, area.right - center.x)
    val roomY = minOf(center.y - area.top, area.bottom - center.y)
    return minOf(1f, roomX / halfWidth, roomY / halfHeight).coerceAtLeast(0f)
}

/**
 * The least scale a turning group's fit in its own room takes it down to (T63.14, Realme feedback 3: the artwork shrank
 * to about 0.71 at 45 degrees); past its room, it may overlap its neighbours (the handle row, the controls).
 */
internal const val LEVEL_MIN_SCALE = 0.85f

/**
 * The scale of [content] turned [degrees] about its own centre: its fit in its own [room] ([levelFitScale]) while that
 * is at least [LEVEL_MIN_SCALE]; below it, the floor, overlapping its neighbours, but never past what the phone's [frame]
 * itself allows (its turned outline never leaves the frame; with no frame known, empty, the room alone decides).
 */
internal fun levelScale(content: Rect, room: Rect, frame: Rect, degrees: Float): Float {
    val fit = levelFitScale(content, room, degrees)
    if (fit >= LEVEL_MIN_SCALE || frame.isEmpty) return fit
    return maxOf(fit, minOf(LEVEL_MIN_SCALE, levelFitScale(content, frame, degrees)))
}

/**
 * Low-pass filter for the accelerometer fallback (phones with no fused gravity sensor): exponential smoothing with
 * [timeConstantSeconds], so a shake or a footstep does not turn the groups. The first reading passes unchanged.
 */
internal class GravityLowPass(private val timeConstantSeconds: Float = 0.15f) {

    private val value = FloatArray(3)
    private var lastNanos = Long.MIN_VALUE

    /** The filtered reading after ([x], [y], [z]) at [timestampNanos]; the returned array is reused. */
    fun filter(x: Float, y: Float, z: Float, timestampNanos: Long): FloatArray {
        if (lastNanos == Long.MIN_VALUE) {
            value[0] = x
            value[1] = y
            value[2] = z
        } else {
            val dt = ((timestampNanos - lastNanos) / 1e9f).coerceAtLeast(0f)
            val alpha = dt / (timeConstantSeconds + dt)
            value[0] += alpha * (x - value[0])
            value[1] += alpha * (y - value[1])
            value[2] += alpha * (z - value[2])
        }
        lastNanos = timestampNanos
        return value
    }
}

/**
 * Adaptive smoothing of every gravity reading, fused or not (T63.16): a one-euro filter on the gravity vector. Its
 * cutoff rises with how fast the vector turns, so a phone held still (a hand's tremor, the fused sensor's own jitter)
 * is smoothed hard and its aim stays inside [GravityLevel]'s dead band, while a real turn opens the filter and is
 * followed with little lag. The first reading passes unchanged.
 *
 * The rate is the vector's turn toward each reading, in degrees a second, itself low-passed at [RATE_CUTOFF_HZ] so a
 * tremor's quick back and forth barely opens the filter; the cutoff is [MIN_CUTOFF_HZ] plus [CUTOFF_PER_DEGREE_PER_SECOND]
 * for each degree a second. Held still with a few degrees of tremor the aim wanders about a degree; a turn at half a turn
 * a second lags about eleven degrees (a sixteenth of a second) and is caught up an eighth of a second after the hand
 * stops, well inside the groups' own critically damped follow.
 */
internal class GravitySmoother {

    private val value = FloatArray(3)
    private var rate = 0f
    private var lastNanos = Long.MIN_VALUE

    /** The smoothed reading after ([x], [y], [z]) at [timestampNanos]; the returned array is reused. */
    fun filter(x: Float, y: Float, z: Float, timestampNanos: Long): FloatArray {
        if (lastNanos == Long.MIN_VALUE) {
            value[0] = x
            value[1] = y
            value[2] = z
            lastNanos = timestampNanos
            return value
        }
        if (timestampNanos <= lastNanos) {
            // No time has passed (or the reading came late, out of order): it has no rate, and folding it in would count
            // its gap as a turn twice. It is skipped and the clock stays at the latest reading folded in; moved back, the
            // next reading would take the step back as its own interval and snap the smoothing open.
            return value
        }
        val dt = (timestampNanos - lastNanos) / 1e9f
        lastNanos = timestampNanos
        val dx = x - value[0]
        val dy = y - value[1]
        val dz = z - value[2]
        val length = sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2])
        // The reading's turn from the smoothed vector (small angle: the gap over the length), in degrees a second. With
        // practically no vector there is no direction to turn from, so the rate is kept as it was.
        if (length >= MIN_LENGTH) {
            val turning = Math.toDegrees((sqrt(dx * dx + dy * dy + dz * dz) / length).toDouble()).toFloat() / dt
            rate += smoothing(dt, RATE_CUTOFF_HZ) * (turning - rate)
        }
        val alpha = smoothing(dt, MIN_CUTOFF_HZ + CUTOFF_PER_DEGREE_PER_SECOND * rate)
        value[0] += alpha * dx
        value[1] += alpha * dy
        value[2] += alpha * dz
        return value
    }

    private companion object {
        /** Held still, the filter's cutoff: its time constant is about four fifths of a second. */
        const val MIN_CUTOFF_HZ = 0.2f

        /** How much each degree a second of turning opens the filter: about 1.4 Hz more at 180 degrees a second. */
        const val CUTOFF_PER_DEGREE_PER_SECOND = 0.008f

        /** The cutoff of the turning rate itself. */
        const val RATE_CUTOFF_HZ = 1f

        /** Below this length (m/s², a tenth of gravity) the smoothed vector has no direction to measure a turn from. */
        const val MIN_LENGTH = 0.981f

        /** The exponential smoothing factor over [dt] seconds for a first-order low-pass at [cutoffHz]. */
        fun smoothing(dt: Float, cutoffHz: Float): Float {
            val tau = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            return dt / (tau + dt)
        }
    }
}

/** [degrees] wrapped into (-180, 180]. */
internal fun wrapDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}
