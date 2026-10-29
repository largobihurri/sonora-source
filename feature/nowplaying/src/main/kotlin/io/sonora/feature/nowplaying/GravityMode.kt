package io.sonora.feature.nowplaying

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.sonora.core.designsystem.motion.prefersReducedMotion
import kotlinx.coroutines.channels.Channel

/**
 * Gravity mode's live level (T63): sensor readings go in ([onReading]), [angle] comes out as state that only layer
 * blocks read, so a turn redraws layers and recomposes nothing. [run] is the frame loop: it sleeps until a reading
 * moves the groups and returns to sleep as soon as they settle. A frame of a turn writes the angle and nothing else
 * (T63.11); what composition needs ([detent], [quarter]) changes only a few times a turn.
 */
@Stable
internal class GravityLevelState {

    private var level = GravityLevel()
    private val angleState = mutableFloatStateOf(0f)
    private val detentState = mutableStateOf<Int?>(null)
    private val quarterState = mutableIntStateOf(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The groups' clockwise turn in degrees (state: read it in a layer block, never in composition). */
    val angle: Float get() = angleState.floatValue

    /** The quarter turn whose magnetic detent holds the groups, null while free ([GravityLevel.detent]; state). */
    val detent: Int? get() = detentState.value

    /** The quarter turn (0..3) nearest the angle, which the floor's side follows ([dismissQuarter]; state). */
    val quarter: Int get() = quarterState.intValue

    /** A gravity reading in the sensor's axes, the display at [displayRotation] quarter turns. */
    fun onReading(x: Float, y: Float, z: Float, displayRotation: Int) {
        val moved = level.onGravity(x, y, z, displayRotation)
        detentState.value = level.detent
        if (moved) {
            show(level.angle)
            wake.trySend(Unit)
        }
    }

    /** Back upright, as if no reading had come (reduced motion turned the effect off). */
    fun reset() {
        level = GravityLevel()
        show(0f)
        detentState.value = null
    }

    private fun show(degrees: Float) {
        angleState.floatValue = degrees
        val nearest = dismissQuarter(degrees)
        if (nearest != quarterState.intValue) quarterState.intValue = nearest
    }

    /** Steps the level on each frame while it moves; suspends without frames while it is settled. */
    suspend fun run() {
        for (signal in wake) {
            var last = -1L
            do {
                val moving = withFrameNanos { now ->
                    val seconds = if (last < 0L) 0f else (now - last) / 1e9f
                    last = now
                    level.step(seconds).also { show(level.angle) }
                }
            } while (moving)
        }
    }
}

/**
 * The level of an open Now Playing in gravity mode ([enabled]): the sensor is registered only while the screen is
 * resumed and the system does not ask for reduced motion ([reducedMotion], read again on each resume), and the frame
 * loop runs only while composed. Disabled, the level stays upright and nothing listens; each time it is enabled it
 * starts afresh (upright, no detent, its first reading placing the groups at once), never from where it was when last
 * disabled (R3-stale-state-on-reenable: a stale sideways detent would latch a sideways destination at once). The angle
 * is in the phone's own portrait frame (display rotation 0), the frame [GravityFrameLayout] lays the groups out in,
 * whichever way the window is turned.
 */
@Composable
internal fun rememberGravityLevel(
    enabled: Boolean,
    reducedMotion: (Context) -> Boolean = { it.prefersReducedMotion() },
): GravityLevelState {
    val state = remember(enabled) { GravityLevelState() }
    if (!enabled) return state
    val context = LocalContext.current
    val reduced by rememberUpdatedState(reducedMotion)
    LifecycleResumeEffect(state, context) {
        val sensor = if (reduced(context)) {
            state.reset()
            null
        } else {
            GravitySensor.start(context) { x, y, z -> state.onReading(x, y, z, displayRotation = 0) }
        }
        onPauseOrDispose { sensor?.stop() }
    }
    LaunchedEffect(state) { state.run() }
    return state
}

/**
 * The phone's gravity: the platform's fused gravity sensor (accelerometer + gyroscope, steady and quick), or the
 * accelerometer through [GravityLowPass] on phones without one. Either goes through a [GravitySmoother] (T63.16): the
 * fused readings carry a hand's tremor too, enough to cross the level's dead band again and again while it is held
 * still. Readings arrive on the main thread at the game rate.
 */
private class GravitySensor private constructor(private val manager: SensorManager, private val listener: SensorEventListener) {

    fun stop() = manager.unregisterListener(listener)

    companion object {
        /** Starts listening, or `null` when the phone has neither sensor. */
        fun start(context: Context, onGravity: (x: Float, y: Float, z: Float) -> Unit): GravitySensor? {
            val manager = context.getSystemService(SensorManager::class.java) ?: return null
            val fused = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val sensor = fused ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
            val lowPass = if (fused == null) GravityLowPass() else null
            val smoother = GravitySmoother()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val raw = event.values
                    val v = lowPass?.filter(raw[0], raw[1], raw[2], event.timestamp) ?: raw
                    val smooth = smoother.filter(v[0], v[1], v[2], event.timestamp)
                    onGravity(smooth[0], smooth[1], smooth[2])
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)) return null
            return GravitySensor(manager, listener)
        }
    }
}

/** A group's layer: its turn, its uniform scale, and the pivot as fractions of the layer's size. */
internal data class LevelLayer(val rotation: Float, val scale: Float, val originX: Float, val originY: Float)

/**
 * The layer of a group laid out over [area] (its own node's size) whose visible parts span [content] (in the same
 * coordinates; empty before they are placed, then the whole area turns): turned [degrees] about the content's centre,
 * shrunk just enough for the turned outline to stay inside the area, but no further than [LEVEL_MIN_SCALE] where the
 * phone's [frame] (in the area's coordinates; empty while unknown) has room for it ([levelScale], T63.14); a [rigid] group
 * keeps its full size at every angle instead (T63.15: gravity mode's controls, one block whose outline may leave its area).
 */
internal fun levelLayer(content: Rect, area: Size, degrees: Float, rigid: Boolean = false, frame: Rect = Rect.Zero): LevelLayer {
    if (area.width <= 0f || area.height <= 0f) return LevelLayer(degrees, 1f, 0.5f, 0.5f)
    val bounds = Rect(Offset.Zero, area)
    val box = if (content.isEmpty) bounds else content
    return LevelLayer(
        rotation = degrees,
        scale = if (rigid) 1f else levelScale(box, bounds, frame, degrees),
        originX = box.center.x / area.width,
        originY = box.center.y / area.height,
    )
}

/**
 * One of gravity mode's groups: the node spanning its area ([hangsLevel]) and the parts that make it up ([levelPart]),
 * whose union is the [content] that turns, inside the phone's [frame] ([levelFrame]).
 */
@Stable
internal class LevelGroup {

    private var area: LayoutCoordinates? = null
    private var areaOuter: LayoutCoordinates? = null
    private var frameNode: LayoutCoordinates? = null
    private val parts = HashMap<Int, LayoutCoordinates>()

    /** The parts' union in the area's own (unturned) coordinates; state, read by the area's layer block. */
    var content by mutableStateOf(Rect.Zero)
        private set

    /** The area's own size; state, read by layers placed relative to the group (gravity mode's handles). */
    var areaSize by mutableStateOf(Size.Zero)
        private set

    /**
     * The phone's frame (gravity mode's padded portrait frame, [GravityFrameLayout]) in the area's own (unturned)
     * coordinates, which the turned group never leaves; empty until both are placed. State, read by the area's layer.
     */
    var frame by mutableStateOf(Rect.Zero)
        private set

    /** The area's node inside its own turning layer, where its parts are measured. */
    fun areaPlaced(coordinates: LayoutCoordinates) {
        area = coordinates
        update()
    }

    /** The area's node outside its own turning layer, where the frame is measured. */
    fun areaOuterPlaced(coordinates: LayoutCoordinates) {
        areaOuter = coordinates
        update()
    }

    /** The node spanning the phone's frame, an ancestor of the area with no layer between them. */
    fun framePlaced(coordinates: LayoutCoordinates) {
        frameNode = coordinates
        update()
    }

    fun partPlaced(index: Int, coordinates: LayoutCoordinates) {
        parts[index] = coordinates
        update()
    }

    private fun update() {
        val outer = areaOuter?.takeIf { it.isAttached }
        val whole = frameNode?.takeIf { it.isAttached }
        val nextFrame = if (outer != null && whole != null) outer.localBoundingBoxOf(whole, clipBounds = false) else Rect.Zero
        if (nextFrame != frame) frame = nextFrame
        val area = area?.takeIf { it.isAttached } ?: return
        val size = area.size.toSize()
        if (size != areaSize) areaSize = size
        var union: Rect? = null
        for (part in parts.values) {
            if (!part.isAttached) continue
            val box = area.localBoundingBoxOf(part, clipBounds = false)
            union = union?.let { Rect(minOf(it.left, box.left), minOf(it.top, box.top), maxOf(it.right, box.right), maxOf(it.bottom, box.bottom)) } ?: box
        }
        val next = union ?: Rect.Zero
        if (next != content) content = next
    }
}

/**
 * Turns this node, the area of [group], by [angle] about the group's content and scales it to fit (see [levelLayer]),
 * within the phone's frame once it is known ([levelFrame]); a [rigid] group turns as one block at full size, nothing in
 * it moving against the rest (T63.15). Read in the layer block: a moving level redraws layers only. Touches follow the
 * layer's transform.
 */
internal fun Modifier.hangsLevel(group: LevelGroup, angle: () -> Float, rigid: Boolean = false): Modifier =
    onPlaced(group::areaOuterPlaced).graphicsLayer {
        val layer = levelLayer(group.content, size, angle(), rigid, group.frame)
        rotationZ = layer.rotation
        scaleX = layer.scale
        scaleY = layer.scale
        transformOrigin = TransformOrigin(layer.originX, layer.originY)
    }.onPlaced(group::areaPlaced)

/** Marks this node as spanning the phone's frame for [group] (an ancestor of its area, with no layer in between). */
internal fun Modifier.levelFrame(group: LevelGroup): Modifier = onPlaced(group::framePlaced)

/** Marks this node as part [index] of [group]'s content (its bounds, unturned, in the group's area). */
internal fun Modifier.levelPart(group: LevelGroup, index: Int): Modifier =
    onPlaced { group.partPlaced(index, it) }
