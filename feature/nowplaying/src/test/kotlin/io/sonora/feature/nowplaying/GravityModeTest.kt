package io.sonora.feature.nowplaying

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import io.sonora.core.designsystem.motion.SheetPresentation
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class GravityModeTest {

    private val g = 9.81f

    /** A 60 Hz frame clock that counts the frames asked for. */
    private class CountingClock : MonotonicFrameClock {
        var frames = 0
        private var now = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            frames++
            now += 16_666_667L
            return onFrame(now)
        }
    }

    @Test
    fun `the frame loop runs only while the groups move, and sleeps once they settle`() = runTest {
        val clock = CountingClock()
        val state = GravityLevelState()
        val loop = launch(clock) { state.run() }

        state.onReading(0f, g, 0f, displayRotation = 0) // placed at once: exactly one frame to draw it
        assertThat(state.angle).isEqualTo(0f)
        advanceUntilIdle()
        val placed = clock.frames
        assertThat(placed).isEqualTo(1)

        state.onReading(0.1f, g, 0f, displayRotation = 0) // a tremor: nothing to draw
        state.onReading(0f, 0f, g, displayRotation = 0) // laid flat: held
        advanceUntilIdle()
        assertThat(clock.frames).isEqualTo(placed)

        state.onReading(g, 0f, 0f, displayRotation = 0) // raised sideways: a quarter turn
        advanceUntilIdle()
        val turned = clock.frames
        assertThat(turned).isGreaterThan(placed + 20) // about half a second at 60 Hz
        assertThat(state.angle).isEqualTo(90f)

        advanceUntilIdle()
        assertThat(clock.frames).isEqualTo(turned)
        loop.cancel()
    }

    @Test
    fun `reset puts the groups back upright and forgets the readings`() {
        val state = GravityLevelState()
        state.onReading(g, 0f, 0f, displayRotation = 0)
        assertThat(state.angle).isEqualTo(90f)
        assertThat(state.detent).isEqualTo(1)
        assertThat(state.quarter).isEqualTo(1)

        state.reset()

        assertThat(state.angle).isEqualTo(0f)
        assertThat(state.detent).isNull()
        assertThat(state.quarter).isEqualTo(0)
        state.onReading(-g, 0f, 0f, displayRotation = 0) // the next first reading places them again
        assertThat(state.angle).isEqualTo(-90f)
    }

    @Test
    fun `turned off and on again while shown, the level starts afresh`() = runTest {
        val ui = HeadlessComposition(this)
        var enabled by mutableStateOf(true)
        var level: GravityLevelState? = null
        ui.setContent { level = rememberGravityLevel(enabled, reducedMotion = { false }) }
        level!!.onReading(g, 0f, 0f, displayRotation = 0)
        assertThat(level!!.detent).isEqualTo(1)

        enabled = false
        ui.frame()
        enabled = true
        ui.frame()

        // Not the sideways angle and detent of before (they would latch a sideways destination at once).
        assertThat(level!!.angle).isEqualTo(0f)
        assertThat(level!!.detent).isNull()
        level!!.onReading(-g, 0f, 0f, displayRotation = 0) // its first reading places it at once
        assertThat(level!!.angle).isEqualTo(-90f)
        ui.dispose()
    }

    // Per-frame work (T63.11): a turn changes the angle and nothing else, and recomposes nothing.

    @Test
    fun `while the groups turn, each frame changes only the angle`() = runTest {
        val changes = ArrayList<Int>()
        val observer = Snapshot.registerApplyObserver { changed, _ -> changes += changed.size }
        val clock = object : MonotonicFrameClock {
            private var now = 0L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                yield()
                now += 16_666_667L
                return onFrame(now).also { Snapshot.sendApplyNotifications() }
            }
        }
        val state = GravityLevelState()
        Snapshot.notifyObjectsInitialized() // so that its writes count as changes, as in a running app
        state.onReading(0f, g, 0f, displayRotation = 0)
        Snapshot.sendApplyNotifications()
        val loop = launch(clock) { state.run() }
        advanceUntilIdle()
        changes.clear()

        state.onReading(g, 0f, 0f, displayRotation = 0) // a quarter turn, caught by the sideways detent
        Snapshot.sendApplyNotifications() // the reading: the detent and the first angle
        val reading = changes.toList()
        changes.clear()
        advanceUntilIdle()

        assertThat(reading.single()).isAtMost(2)
        assertThat(changes.size).isGreaterThan(20) // about half a second of frames
        // One state a frame (the angle), two on the one frame the groups pass half way (the nearest quarter turn).
        assertThat(changes.count { it == 2 }).isAtMost(1)
        assertThat(changes.all { it in 1..2 }).isTrue()
        loop.cancel()
        observer.dispose()
    }

    @Test
    fun `a turn recomposes nothing that reads the level, and the sheet's side changes once per quarter turn`() = runTest {
        val ui = HeadlessComposition(this)
        val sheet = SheetPresentation(initiallyOpen = true, scope = backgroundScope, reducedMotion = { false })
        val detents = ArrayList<Int>()
        var level: GravityLevelState? = null
        var recompositions = 0
        var side = 0
        ui.setContent {
            val state = rememberGravityLevel(enabled = true, reducedMotion = { false })
            level = state
            val slide = rememberGravitySheetSide(state, gravity = true, sheet, remember { mutableIntStateOf(0) }, onDetent = { detents += it })
            recompositions++
            side = slide.intValue // read in composition, as the sheet's drag direction is
        }
        level!!.onReading(0f, g, 0f, displayRotation = 0)
        repeat(5) { ui.frame() }
        val before = recompositions

        level!!.onReading(reading(40f)[0], reading(40f)[1], 0f, displayRotation = 0) // free, inside the same quarter
        repeat(60) { ui.frame() }
        assertThat(level!!.angle).isWithin(0.01f).of(40f)
        assertThat(recompositions).isEqualTo(before)

        level!!.onReading(g, 0f, 0f, displayRotation = 0) // on to sideways: the sheet now leaves to the left
        repeat(60) { ui.frame() }
        assertThat(level!!.angle).isEqualTo(90f)
        assertThat(side).isEqualTo(1)
        assertThat(recompositions).isEqualTo(before + 1)
        assertThat(detents).containsExactly(0, 1).inOrder()
        ui.dispose()
    }

    /** Gravity as read for a phone turned [degrees] clockwise, held upright. */
    private fun reading(degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        return floatArrayOf(g * kotlin.math.sin(radians).toFloat(), g * kotlin.math.cos(radians).toFloat())
    }

    // The sheet's side (T63.6, R3-001)

    @Test
    fun `the sheet takes the floor's side only while it rests open, and goes back to the bottom once closed`() {
        // Resting open: follows the floor.
        assertThat(slideQuarterAfter(current = 0, quarter = 1, covers = true, closed = false)).isEqualTo(1)
        // A finger moving it, or sliding open or away: keeps the side it had (never a turn mid-gesture).
        assertThat(slideQuarterAfter(current = 1, quarter = 2, covers = false, closed = false)).isEqualTo(1)
        // Closed: back to the bottom, where the mini player raises it from.
        assertThat(slideQuarterAfter(current = 1, quarter = 1, covers = false, closed = true)).isEqualTo(0)
        // Opened again before it was quite gone: it comes back the way it left, and takes the floor once it rests.
        assertThat(slideQuarterAfter(current = 3, quarter = 0, covers = false, closed = false)).isEqualTo(3)
        assertThat(slideQuarterAfter(current = 3, quarter = 0, covers = true, closed = false)).isEqualTo(0)
    }

    @Test
    fun `the window's quarter turn comes off the level's own`() {
        assertThat(windowQuarter(levelQuarter = 1, displayRotation = 0)).isEqualTo(1)
        assertThat(windowQuarter(levelQuarter = 1, displayRotation = 1)).isEqualTo(0)
        assertThat(windowQuarter(levelQuarter = 0, displayRotation = 1)).isEqualTo(3)
        assertThat(windowQuarter(levelQuarter = 3, displayRotation = 3)).isEqualTo(0)
        // As the angle itself would give it.
        for (degrees in listOf(-170f, -95f, -10f, 30f, 60f, 100f, 200f, 300f, 460f)) {
            for (rotation in 0..3) {
                assertThat(windowQuarter(dismissQuarter(degrees), rotation)).isEqualTo(dismissQuarter(windowAngle(degrees, rotation)))
            }
        }
    }

    // A group's parts (R3-levelgroup-gating)

    /** Layout coordinates of a node at [bounds] in its group's area (the area itself at the origin). */
    private class Placed(var bounds: Rect, override var isAttached: Boolean = true) : LayoutCoordinates {
        override val size get() = IntSize(bounds.width.toInt(), bounds.height.toInt())
        override val providedAlignmentLines: Set<AlignmentLine> get() = emptySet()
        override val parentLayoutCoordinates: LayoutCoordinates? get() = null
        override val parentCoordinates: LayoutCoordinates? get() = null
        override fun windowToLocal(relativeToWindow: Offset) = relativeToWindow - bounds.topLeft
        override fun localToWindow(relativeToLocal: Offset) = relativeToLocal + bounds.topLeft
        override fun localToRoot(relativeToLocal: Offset) = relativeToLocal + bounds.topLeft
        override fun localPositionOf(sourceCoordinates: LayoutCoordinates, relativeToSource: Offset) =
            relativeToSource + (sourceCoordinates as Placed).bounds.topLeft - bounds.topLeft
        override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean) =
            (sourceCoordinates as Placed).bounds.translate(-bounds.topLeft)
        override fun get(alignmentLine: AlignmentLine) = AlignmentLine.Unspecified
    }

    /** Counts the state writes [block] makes. */
    private fun writes(block: () -> Unit): Int {
        var count = 0
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { count++ })
        try {
            snapshot.enter(block)
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        return count
    }

    @Test
    fun `a group's content is the union of its placed parts, and nothing is written when nothing moved`() {
        val group = LevelGroup()
        val area = Placed(Rect(0f, 0f, 1000f, 800f))
        val first = Placed(Rect(100f, 100f, 300f, 200f))
        val second = Placed(Rect(400f, 100f, 600f, 200f))

        group.areaPlaced(area)
        group.partPlaced(1, second)
        assertThat(group.content).isEqualTo(Rect(400f, 100f, 600f, 200f))

        group.partPlaced(0, first)
        assertThat(group.content).isEqualTo(Rect(100f, 100f, 600f, 200f))
        assertThat(group.areaSize).isEqualTo(Size(1000f, 800f))

        // Placed again where they were (a relayout around them): no state written, so no layer reads anew.
        assertThat(writes { group.partPlaced(0, first); group.areaPlaced(area) }).isEqualTo(0)

        // One moves: published once more.
        second.bounds = Rect(420f, 100f, 620f, 200f)
        assertThat(writes { group.partPlaced(1, second) }).isAtLeast(1)
        assertThat(group.content).isEqualTo(Rect(100f, 100f, 620f, 200f))

        // One leaves the composition: the content is what is still there.
        second.isAttached = false
        group.partPlaced(0, first)
        assertThat(group.content).isEqualTo(first.bounds)
    }

    /** Where [layer], on an area of [size], draws the area's [point]. */
    private fun drawn(layer: LevelLayer, size: Size, point: Offset): Offset {
        val origin = Offset(layer.originX * size.width, layer.originY * size.height)
        val radians = Math.toRadians(layer.rotation.toDouble())
        val c = kotlin.math.cos(radians).toFloat()
        val s = kotlin.math.sin(radians).toFloat()
        val d = (point - origin) * layer.scale
        return origin + Offset(d.x * c - d.y * s, d.x * s + d.y * c)
    }

    @Test
    fun `the controls turn as one rigid block at full size, every element keeping its place among the others`() {
        // T63.15 (the user's call after Realme feedback 3): no per-element fit; overlapping the artwork or the window's
        // edges is accepted. A phone's controls band, in px: the title row, the scrubber, the five transport buttons,
        // the volume slider and the bottom row.
        val size = Size(1080f, 800f)
        val elements = listOf(
            Rect(74f, 40f, 1006f, 190f), Rect(74f, 210f, 1006f, 300f), Rect(74f, 330f, 174f, 430f),
            Rect(260f, 320f, 400f, 440f), Rect(470f, 315f, 610f, 445f), Rect(680f, 320f, 820f, 440f),
            Rect(906f, 330f, 1006f, 430f), Rect(74f, 500f, 1006f, 580f), Rect(74f, 620f, 1006f, 768f),
        )
        val content = Rect(74f, 40f, 1006f, 768f) // their union: the controls' column
        var degrees = 0f
        while (degrees <= 360f) {
            val layer = levelLayer(content, size, degrees, rigid = true)

            assertThat(layer.scale).isEqualTo(1f)
            assertThat(layer.rotation).isEqualTo(degrees)
            // About the controls' own centre, which stays where it was laid out.
            assertThat(drawn(layer, size, content.center).x).isWithin(0.01f).of(content.center.x)
            assertThat(drawn(layer, size, content.center).y).isWithin(0.01f).of(content.center.y)
            val centres = elements.map { drawn(layer, size, it.center) }
            for (i in elements.indices) for (j in elements.indices) {
                val laidOut = (elements[i].center - elements[j].center).getDistance()
                val turned = (centres[i] - centres[j]).getDistance()
                assertWithMessage("elements $i and $j at $degrees").that(turned).isWithin(0.05f).of(laidOut)
            }
            degrees += 7.5f
        }
    }

    @Test
    fun `a group's layer turns about its content's centre and shrinks it into its area`() {
        val area = Size(1000f, 1200f)
        val content = Rect(100f, 100f, 900f, 1100f)

        val upright = levelLayer(content, area, 0f)
        assertThat(upright.scale).isEqualTo(1f)
        assertThat(upright.originX).isWithin(0.001f).of(0.5f)
        assertThat(upright.originY).isWithin(0.001f).of(0.5f)

        val turned = levelLayer(content, area, 45f)
        assertThat(turned.rotation).isEqualTo(45f)
        assertThat(turned.scale).isWithin(0.001f).of(levelFitScale(content, Rect(0f, 0f, 1000f, 1200f), 45f))
    }

    @Test
    fun `given the phone's frame, a group's layer shrinks no further than the floor the frame allows`() {
        val area = Size(1080f, 1000f) // the artwork's room
        val content = Rect(78f, 38f, 1002f, 962f)
        val frame = Rect(0f, -58f, 1080f, 2172f) // the padded phone frame, in the room's coordinates

        val turned = levelLayer(content, area, 20f, frame = frame)

        assertThat(turned.scale).isEqualTo(LEVEL_MIN_SCALE)
        assertThat(turned.scale).isGreaterThan(levelLayer(content, area, 20f).scale)
        assertThat(levelLayer(content, area, 45f, frame = frame).scale).isWithin(0.001f).of(levelScale(content, Rect(Offset.Zero, area), frame, 45f))
        // A rigid group ignores it: full size.
        assertThat(levelLayer(content, area, 20f, rigid = true, frame = frame).scale).isEqualTo(1f)
    }

    @Test
    fun `a group knows the phone's frame in its own area's coordinates, written only when it moves`() {
        val group = LevelGroup()
        val frame = Placed(Rect(0f, 0f, 1080f, 2230f)) // the frame's padded content
        val outer = Placed(Rect(0f, 58f, 1080f, 1130f)) // the area, outside its own turning layer
        assertThat(group.frame).isEqualTo(Rect.Zero) // not known yet

        group.framePlaced(frame)
        group.areaOuterPlaced(outer)

        assertThat(group.frame).isEqualTo(Rect(0f, -58f, 1080f, 2172f))
        assertThat(writes { group.framePlaced(frame); group.areaOuterPlaced(outer) }).isEqualTo(0)
        outer.bounds = Rect(0f, 70f, 1080f, 1130f)
        group.areaOuterPlaced(outer)
        assertThat(group.frame).isEqualTo(Rect(0f, -70f, 1080f, 2160f))
    }

    @Test
    fun `a group whose parts are not placed yet turns as a whole, and an empty area stays put`() {
        val whole = levelLayer(Rect.Zero, Size(400f, 200f), 90f)
        assertThat(whole.originX).isWithin(0.001f).of(0.5f)
        assertThat(whole.originY).isWithin(0.001f).of(0.5f)
        assertThat(whole.scale).isWithin(0.001f).of(0.5f) // 200 x 400 turned into 400 x 200

        val empty = levelLayer(Rect.Zero, Size.Zero, 90f)
        assertThat(empty.scale).isEqualTo(1f)
        assertThat(empty.rotation).isEqualTo(90f)
    }

    @Test
    fun `off-centre content turns about its own centre, not the area's`() {
        val layer = levelLayer(Rect(300f, 0f, 700f, 400f), Size(1000f, 1000f), 30f)

        assertThat(layer.originX).isWithin(0.001f).of(0.5f)
        assertThat(layer.originY).isWithin(0.001f).of(0.2f)
    }

    // The drag handle (T63.6)

    @Test
    fun `each handle shows fully at its own quarter turn and fades out as the phone tilts away`() {
        assertThat(handleAlpha(0, 0f)).isEqualTo(1f)
        assertThat(handleAlpha(0, HANDLE_FADE_DEGREES / 2f)).isWithin(0.001f).of(0.5f)
        assertThat(handleAlpha(0, -HANDLE_FADE_DEGREES / 2f)).isWithin(0.001f).of(0.5f)
        assertThat(handleAlpha(0, HANDLE_FADE_DEGREES)).isEqualTo(0f)
        assertThat(handleAlpha(0, 90f)).isEqualTo(0f)

        assertThat(handleAlpha(1, 90f)).isEqualTo(1f)
        assertThat(handleAlpha(1, 450f)).isEqualTo(1f) // one whole turn on
        assertThat(handleAlpha(2, -180f)).isEqualTo(1f)
        assertThat(handleAlpha(3, -90f)).isEqualTo(1f)
        assertThat(handleAlpha(3, 270f)).isEqualTo(1f)

        // Half way between two quarter turns neither shows: a cross-fade, never two handles at once in full.
        for (degrees in listOf(0f, 20f, 45f, 70f, 90f, 135f, 200f, 300f)) {
            val total = (0..3).sumOf { handleAlpha(it, degrees).toDouble() }
            assertThat(total).isAtMost(1.0001)
        }
    }

    @Test
    fun `a handle sits just beyond the artwork's gravity-up edge, drawn upright for its quarter turn`() {
        val area = Rect(0f, 0f, 1000f, 1200f)
        val artwork = Rect(100f, 200f, 900f, 1000f) // 800 square, centre (500, 600)
        val gap = 30f

        val right = handleLayer(1, artwork, area, degrees = 90f, offset = gap)
        assertThat(right.center.x).isWithin(0.01f).of(500f + 400f + gap)
        assertThat(right.center.y).isWithin(0.01f).of(600f)
        assertThat(right.rotation).isEqualTo(90f)
        assertThat(right.alpha).isEqualTo(1f)

        val below = handleLayer(2, artwork, area, degrees = 180f, offset = gap)
        assertThat(below.center.x).isWithin(0.01f).of(500f)
        assertThat(below.center.y).isWithin(0.01f).of(600f + 400f + gap)
        assertThat(below.rotation).isEqualTo(180f)

        val left = handleLayer(3, artwork, area, degrees = 0f, offset = gap)
        assertThat(left.center.x).isWithin(0.01f).of(500f - 400f - gap)
        assertThat(left.center.y).isWithin(0.01f).of(600f)
        assertThat(left.rotation).isEqualTo(270f)
        assertThat(left.alpha).isEqualTo(0f) // upright: the left one does not show
    }

    @Test
    fun `a handle follows the artwork in when the artwork must shrink at its quarter turn, and stays put meanwhile`() {
        val area = Rect(0f, 0f, 500f, 1000f)
        val pane = Rect(50f, 200f, 450f, 800f) // 400 x 600, centre (250, 500): turned sideways it must fit 500 wide
        val scale = levelFitScale(pane, area, 90f)
        assertThat(scale).isWithin(0.001f).of(500f / 600f)

        val right = handleLayer(1, pane, area, degrees = 90f, offset = 10f)
        assertThat(right.center.x).isWithin(0.01f).of(250f + 300f * scale + 10f)

        // Its place depends only on its own quarter turn: as the phone tilts it fades, it does not move.
        val tilted = handleLayer(1, pane, area, degrees = 70f, offset = 10f)
        assertThat(tilted.center).isEqualTo(right.center)
        assertThat(tilted.alpha).isLessThan(1f)
    }

    // Dismissing toward the floor

    @Test
    fun `Now Playing is dismissed toward the floor, whichever way the groups hang`() {
        assertThat(dismissQuarter(0f)).isEqualTo(0)
        assertThat(dismissQuarter(5f)).isEqualTo(0)
        assertThat(dismissQuarter(80f)).isEqualTo(1)
        assertThat(dismissQuarter(-90f)).isEqualTo(3)
        assertThat(dismissQuarter(185f)).isEqualTo(2)
        assertThat(dismissQuarter(450f)).isEqualTo(1)

        assertThat(dismissOrientation(0)).isEqualTo(Orientation.Vertical)
        assertThat(dismissOrientation(1)).isEqualTo(Orientation.Horizontal)
        assertThat(dismissOrientation(2)).isEqualTo(Orientation.Vertical)
        assertThat(dismissOrientation(3)).isEqualTo(Orientation.Horizontal)
    }

    @Test
    fun `the sheet slides toward the floor and a finger moving that way drags it down one to one`() {
        val size = Size(1000f, 2000f)

        assertThat(sheetSlide(0, 0.5f, size)).isEqualTo(Offset(0f, 1000f))
        // Hanging a quarter turn clockwise (the phone's right edge up), the floor is on the screen's left.
        assertThat(sheetSlide(1, 0.5f, size)).isEqualTo(Offset(-500f, 0f))
        assertThat(sheetSlide(2, 0.5f, size)).isEqualTo(Offset(0f, -1000f))
        assertThat(sheetSlide(3, 0.5f, size)).isEqualTo(Offset(500f, 0f))

        // The sheet's progress is counted in its height; a sideways travel is the width.
        assertThat(dismissDrag(0, 30f, size)).isEqualTo(30f)
        assertThat(dismissDrag(1, -30f, size)).isEqualTo(60f)
        assertThat(dismissDrag(2, -30f, size)).isEqualTo(30f)
        assertThat(dismissDrag(3, 30f, size)).isEqualTo(60f)
        assertThat(dismissDrag(1, 30f, Size.Zero)).isZero()
    }
}
