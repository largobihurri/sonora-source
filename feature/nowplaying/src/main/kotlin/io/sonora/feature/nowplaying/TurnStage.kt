package io.sonora.feature.nowplaying

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import io.sonora.core.designsystem.motion.prefersReducedMotion
import kotlinx.coroutines.delay

/**
 * Now Playing's two arrangements as one live scene, the way the tabs slide as one strip. While turns are [enabled] (and
 * a moment after, [keepsBoth]) both arrangements stay composed and laid out, each at its own shape
 * ([arrangementSize]) and with the safe insets of its own orientation, both from the window's origin; the one for the
 * window's shape is shown. A rotation of the window swaps its size but not theirs, so neither is composed nor measured
 * again: a turn only changes the layer properties of their blocks ([BlockTurn]), the outgoing arrangement drawn above
 * the incoming one. An arrangement not taking part is neither drawn, touched nor read by TalkBack.
 *
 * Turns disabled, only the shown arrangement is composed and a rotation simply shows the other one.
 */
@Stable
internal class TurnStage(
    /** The display's rotation now (Surface.ROTATION_*). */
    private val displayRotation: () -> Int,
    /** A monotonic clock in milliseconds, for the wait of a turning window ([WINDOW_TURN_WAIT_MILLIS]). */
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    /** Whether a rotation turns the blocks; read when the window turns, so it is the value from before the rotation. */
    var enabled = false

    /** Whether the arrangement not shown is kept composed and laid out, ready for the next turn. */
    var keepsBoth by mutableStateOf(false)

    /** Which arrangement is shown and which, during a turn, turns away. */
    var roles by mutableStateOf(TurnRoles(NowPlayingLayout.Portrait))
        private set

    /** 0 to 1 through the current turn, by time; 1 when none runs. */
    var progress by mutableFloatStateOf(1f)

    /** Counts turns, so a new one restarts the animation. */
    var turnCount by mutableIntStateOf(0)
        private set

    /** Bumped when the running turn is planned again (a block of the incoming arrangement placed), for the layers. */
    private var turnVersion by mutableIntStateOf(0)

    private var turn: BlockTurn? = null
    private var rotation = displayRotation()
    private var root: IntSize? = null

    /** A quarter turn of the display whose window has not changed size yet. */
    private var waiting = false

    /** When the current wait began. */
    private var waitingSinceMs = 0L
    private val insets = InsetMemory()
    private val places = HashMap<NowPlayingLayout, HashMap<NowPlayingBlock, Rect>>()

    /** The node that last reported each block's place. */
    private val owners = HashMap<Pair<NowPlayingLayout, NowPlayingBlock>, Any>()

    /** The nodes whose placement applies the turn's layers (see [applyNow]). */
    private val layerNodes = LinkedHashSet<LayoutModifierNode>()

    /** Where the arrangements are placed: the blocks' places are taken in these coordinates. */
    private var coordinates: LayoutCoordinates? = null

    /** The stage is measured in a window of [root] size whose safe insets are [live]: follows a turn of the window. */
    fun measured(root: IntSize, live: SafeInsets) {
        val last = this.root
        this.root = root
        if (last == null) {
            rotation = displayRotation()
            roles = TurnRoles(nowPlayingLayout(root))
        } else {
            follow(last, root)
        }
        if (!waiting) insets.shown(roles.shown, live)
        updateClearances(live)
    }

    /** Each arrangement's [topRowClearance] (state, read by its controls' layout), from its padding with [live] insets. */
    private val clearances = NowPlayingLayout.entries.associateWith { mutableIntStateOf(0) }
    private var lastLive = SafeInsets.Zero

    /** How far the controls' top row of [layout] moves down to clear the window's top bar (T71); state. */
    fun topRowClearance(layout: NowPlayingLayout): Int = clearances.getValue(layout).intValue

    private fun updateClearances(live: SafeInsets) {
        lastLive = live
        for ((layout, clearance) in clearances) {
            val next = topRowClearance(paddingFor(layout, live))
            if (clearance.intValue != next) clearance.intValue = next
        }
    }

    /** A half turn (90 to 270 degrees) leaves the window's size as it was, so no measure sees it; the composition does. */
    fun checkHalfTurn() {
        val root = root ?: return
        follow(root, root)
        updateClearances(lastLive)
    }

    private fun follow(last: IntSize, root: IntSize) {
        val now = displayRotation()
        var change = windowTurn(rotation, now, last, root)
        if (change == WindowTurn.Wait) {
            // The window resizes a frame or two after the display turns. One that has not done so by now never will
            // (it keeps its size, as in multi-window): the turn is skipped, so the wait cannot keep the insets frozen
            // (R3-wait-latch).
            if (!waiting) waitingSinceMs = nowMs()
            if (nowMs() - waitingSinceMs >= WINDOW_TURN_WAIT_MILLIS) change = WindowTurn.Skip
        }
        waiting = change == WindowTurn.Wait
        when (change) {
            WindowTurn.None -> if (nowPlayingLayout(root) != roles.shown) show(nowPlayingLayout(root))
            WindowTurn.Wait -> Unit
            WindowTurn.Skip -> {
                rotation = now
                show(nowPlayingLayout(root))
            }
            is WindowTurn.Turn -> {
                rotation = now
                startTurn(change.quarterTurns, last, root)
            }
        }
    }

    private fun startTurn(quarterTurns: Int, oldRoot: IntSize, root: IntSize) {
        val next = nowPlayingLayout(root)
        val old = places[roles.shown]
        if (!enabled || old.isNullOrEmpty()) {
            show(next)
            return
        }
        val turned = roles.turnedTo(next)
        turn = BlockTurn(
            incoming = next,
            outgoing = turned.outgoing,
            old = old.toMap(),
            oldScreen = oldRoot.toSize(),
            quarterTurns = quarterTurns,
            ends = places[next].orEmpty().toMap(),
            screen = root.toSize(),
        )
        roles = turned
        progress = 0f
        turnCount++
        applyNow()
    }

    /** Shows [layout] without a turn. */
    private fun show(layout: NowPlayingLayout) {
        turn = null
        progress = 1f
        roles = TurnRoles(layout)
        applyNow()
    }

    /**
     * Places the blocks and the arrangements again, so that their layers take the new roles and the turn's start on
     * this very frame: a layer block runs when it is placed anew, while a state change reaches it a frame later.
     */
    private fun applyNow() {
        for (node in layerNodes) node.invalidatePlacement()
    }

    /** Ends turn number [count], unless a newer turn has started since. */
    fun finished(count: Int) {
        if (count != turnCount) return
        turn = null
        progress = 1f
        roles = roles.settled()
    }

    /**
     * The padding of [layout]'s arrangement: by the window's [live] insets while it is shown, its remembered ones otherwise
     * (while the shown one waits for a turning window, with the live top bar: it is on the display now).
     */
    fun paddingFor(layout: NowPlayingLayout, live: SafeInsets): SafeInsets = arrangementPadding(
        layout,
        when {
            layout != roles.shown -> insets.insetsFor(layout)
            waiting -> insets.insetsFor(layout).copy(bar = live.bar)
            else -> live
        },
    )

    /** The arrangements were placed in these [coordinates]. */
    fun placedIn(coordinates: LayoutCoordinates?) {
        this.coordinates = coordinates
    }

    /** [block] of [arrangement], held by the node [owner], is laid out at [coordinates] (outside its own turn layer). */
    fun positioned(arrangement: NowPlayingLayout, block: NowPlayingBlock, coordinates: LayoutCoordinates, owner: Any) {
        val stage = this.coordinates?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        placed(arrangement, block, Rect(stage.localPositionOf(coordinates, Offset.Zero), coordinates.size.toSize()), owner)
    }

    /** [block] of [arrangement] is at [place] in the stage's coordinates, reported by [owner]. */
    fun placed(arrangement: NowPlayingLayout, block: NowPlayingBlock, place: Rect, owner: Any) {
        owners[arrangement to block] = owner
        if (places.getOrPut(arrangement, ::HashMap).put(block, place) == place) return
        val turn = turn ?: return
        if (arrangement == turn.incoming && turn.placed(block, place, progress)) turnVersion++
    }

    /**
     * [block] of [arrangement] left the composition. Its place goes only if [owner] was the last to report it: while the
     * DJ switch fades one arrangement out over its replacement, both report the same block, and the one leaving must not
     * erase the place the other reported.
     */
    fun forget(arrangement: NowPlayingLayout, block: NowPlayingBlock, owner: Any) {
        if (owners[arrangement to block] !== owner) return
        owners.remove(arrangement to block)
        places[arrangement]?.remove(block)
    }

    /**
     * Where [block] of the arrangement shown is laid out, in the stage's coordinates (unmoved by any turn), or null
     * until it is placed; read at draw time (e.g. as a shared artwork's target, see [ArtworkShareTarget]).
     */
    fun shownPlace(block: NowPlayingBlock): Rect? = places[roles.shown]?.get(block)

    fun attach(node: LayoutModifierNode) {
        layerNodes += node
    }

    fun detach(node: LayoutModifierNode) {
        layerNodes -= node
    }

    /** Sets [scope] to draw [block] of [arrangement] through the current turn; [opaque] as for [liveAlpha]. */
    fun applyBlockLayer(scope: GraphicsLayerScope, arrangement: NowPlayingLayout, block: NowPlayingBlock, opaque: Boolean) {
        turnVersion // read: a turn planned again draws its blocks again
        val layer = stageBlockLayer(roles.role(arrangement), turn, arrangement, block, places[arrangement]?.get(block), progress, opaque)
        scope.translationX = layer.translation.x
        scope.translationY = layer.translation.y
        scope.rotationZ = layer.rotation
        scope.scaleX = layer.scale
        scope.scaleY = layer.scale
        scope.alpha = layer.alpha
    }

    /**
     * Sets [scope] for [arrangement] as a whole: a hidden arrangement clips to nothing, so it is not drawn, and touches
     * miss it (a pointer outside a clipping layer reaches none of its children).
     */
    fun applyArrangementLayer(scope: GraphicsLayerScope, arrangement: NowPlayingLayout) {
        val hidden = roles.role(arrangement) == ArrangementRole.Hidden
        scope.clip = hidden
        scope.shape = if (hidden) NoOutline else RectangleShape
    }
}

/** A shape with an empty outline: a layer clipped to it draws nothing and takes no touch. */
private val NoOutline = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rectangle(Rect.Zero)
}

/** How long turns are enabled before the other arrangement is composed: after the sheet's opening, not during it. */
private const val WARM_UP_MILLIS = 500L

/**
 * How long a quarter turn of the display waits for the window to resize before it is taken as not coming: the turn is
 * then skipped and the shown arrangement follows its window's insets again.
 */
internal const val WINDOW_TURN_WAIT_MILLIS = 500L

/**
 * The stage of Now Playing's arrangements; [turnsBlocks] while a rotation should turn them (the shell then asks the
 * window for a seamless rotation), otherwise a rotation simply shows the new layout. Reduced motion skips the turns.
 */
@Composable
internal fun rememberTurnStage(turnsBlocks: Boolean): TurnStage {
    val view = LocalView.current
    val context = LocalContext.current
    val stage = remember(view) { TurnStage(displayRotation = { view.displayRotation() }) }
    val enabled = turnsBlocks && !context.prefersReducedMotion()
    SideEffect { stage.enabled = enabled }
    LaunchedEffect(stage, enabled) {
        if (enabled) delay(WARM_UP_MILLIS)
        stage.keepsBoth = enabled
    }
    HalfTurnWatch(stage)
    val count = stage.turnCount
    LaunchedEffect(stage, count) {
        if (stage.progress >= 1f) return@LaunchedEffect
        try {
            animate(0f, 1f, animationSpec = tween(TURN_MILLIS, easing = LinearEasing)) { value, _ -> stage.progress = value }
        } finally {
            stage.finished(count)
        }
    }
    return stage
}

/** Recomposes on a configuration change (the display rotation among it) to look for a half turn. */
@Composable
private fun HalfTurnWatch(stage: TurnStage) {
    LocalConfiguration.current
    SideEffect { stage.checkHalfTurn() }
}

/**
 * The role of the arrangement around, read as state: [ArrangementRole.Shown] outside a [TurnStageLayout]. Parts that
 * would keep animating or stay open (marquees, spinners, menus) stop while it is not shown.
 */
internal val LocalArrangementRole = staticCompositionLocalOf<() -> ArrangementRole> { { ArrangementRole.Shown } }

/**
 * The window's top bar (T71): the status bar whether or not the system counts it as shown (ColorOS leaves it out of the
 * safe insets in landscape while drawing its icons), or a cutout on top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun windowTopBar(): WindowInsets = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top))

/**
 * Lays out the arrangements of [stage], [content] for each, as [TurnStage] describes: each measured at its own shape
 * less its safe insets, placed from the window's origin (inset by them), the one turning away above the other.
 */
@Composable
internal fun TurnStageLayout(stage: TurnStage, modifier: Modifier = Modifier, content: @Composable (NowPlayingLayout) -> Unit) {
    val insets = WindowInsets.safeDrawing
    val bar = windowTopBar()
    val slots: Map<NowPlayingLayout, @Composable () -> Unit> = remember(stage, content) {
        NowPlayingLayout.entries.associateWith { layout -> { StagedArrangement(stage, layout, content) } }
    }
    SubcomposeLayout(modifier) { constraints ->
        val root = IntSize(constraints.maxWidth, constraints.maxHeight)
        val live = SafeInsets(
            left = insets.getLeft(this, layoutDirection),
            top = insets.getTop(this),
            right = insets.getRight(this, layoutDirection),
            bottom = insets.getBottom(this),
            bar = maxOf(bar.getTop(this), insets.getTop(this)),
        )
        stage.measured(root, live)
        val roles = stage.roles
        val arrangements = roles.composed(stage.keepsBoth).map { layout ->
            val size = arrangementSize(layout, root)
            val padding = stage.paddingFor(layout, live)
            val inner = Constraints.fixed(
                width = (size.width - padding.horizontal).coerceAtLeast(0),
                height = (size.height - padding.vertical).coerceAtLeast(0),
            )
            Triple(roles.role(layout), padding, subcompose(layout, slots.getValue(layout)).first().measure(inner))
        }
        layout(root.width, root.height) {
            stage.placedIn(coordinates)
            for ((role, padding, placeable) in arrangements) {
                placeable.place(padding.left, padding.top, zIndex = if (role == ArrangementRole.Outgoing) 1f else 0f)
            }
        }
    }
}

@Composable
private fun StagedArrangement(stage: TurnStage, layout: NowPlayingLayout, content: @Composable (NowPlayingLayout) -> Unit) {
    val role = remember(stage, layout) { { stage.roles.role(layout) } }
    CompositionLocalProvider(LocalArrangementRole provides role) {
        Box(Modifier.fillMaxSize().then(StagedArrangementElement(stage, layout)), propagateMinConstraints = true) {
            content(layout)
        }
    }
}

/**
 * Reports this block's place to [stage] and draws it through the current turn. [opaque]: the block shows the same
 * opaque picture before and after the turn (the artwork), so its new rendering waits beneath the old one's fade.
 */
internal fun Modifier.turningBlock(stage: TurnStage, arrangement: NowPlayingLayout, block: NowPlayingBlock, opaque: Boolean = false): Modifier =
    this then BlockPlaceElement(stage, arrangement, block) then BlockLayerElement(stage, arrangement, block, opaque)

private data class BlockPlaceElement(val stage: TurnStage, val arrangement: NowPlayingLayout, val block: NowPlayingBlock) :
    ModifierNodeElement<BlockPlaceNode>() {
    override fun create() = BlockPlaceNode(stage, arrangement, block)

    override fun update(node: BlockPlaceNode) {
        node.stage = stage
        node.arrangement = arrangement
        node.block = block
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "turningBlockPlace"
        properties["arrangement"] = arrangement
        properties["block"] = block
    }
}

/**
 * Follows a block's place. It comes before the block's [BlockLayerNode], so its coordinates are those of the block as
 * laid out, unmoved by the turn's layer.
 */
private class BlockPlaceNode(var stage: TurnStage, var arrangement: NowPlayingLayout, var block: NowPlayingBlock) :
    Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = stage.positioned(arrangement, block, coordinates, this)

    override fun onDetach() = stage.forget(arrangement, block, this)
}

private data class BlockLayerElement(
    val stage: TurnStage,
    val arrangement: NowPlayingLayout,
    val block: NowPlayingBlock,
    val opaque: Boolean,
) : ModifierNodeElement<BlockLayerNode>() {
    override fun create() = BlockLayerNode(stage, arrangement, block, opaque)

    override fun update(node: BlockLayerNode) = node.update(stage, arrangement, block, opaque)

    override fun InspectorInfo.inspectableProperties() {
        name = "turningBlock"
        properties["arrangement"] = arrangement
        properties["block"] = block
        properties["opaque"] = opaque
    }
}

/** Draws a block through the layer [TurnStage.applyBlockLayer] sets. */
private class BlockLayerNode(
    var stage: TurnStage,
    var arrangement: NowPlayingLayout,
    var block: NowPlayingBlock,
    var opaque: Boolean,
) : Modifier.Node(), LayoutModifierNode {
    fun update(stage: TurnStage, arrangement: NowPlayingLayout, block: NowPlayingBlock, opaque: Boolean) {
        if (stage !== this.stage && isAttached) {
            this.stage.detach(this)
            stage.attach(this)
        }
        this.stage = stage
        this.arrangement = arrangement
        this.block = block
        this.opaque = opaque
    }

    override fun onAttach() = stage.attach(this)

    override fun onDetach() = stage.detach(this)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // A new layer block on every placement: placed again when a turn starts, the block takes it on that frame.
            placeable.placeWithLayer(0, 0) { stage.applyBlockLayer(this, arrangement, block, opaque) }
        }
    }
}

private data class StagedArrangementElement(val stage: TurnStage, val arrangement: NowPlayingLayout) :
    ModifierNodeElement<StagedArrangementNode>() {
    override fun create() = StagedArrangementNode(stage, arrangement)

    override fun update(node: StagedArrangementNode) = node.update(stage, arrangement)

    override fun InspectorInfo.inspectableProperties() {
        name = "stagedArrangement"
        properties["arrangement"] = arrangement
    }
}

/**
 * One arrangement as a whole: hidden, it is clipped to nothing ([TurnStage.applyArrangementLayer]) and its semantics are
 * cleared, so TalkBack does not read it.
 */
private class StagedArrangementNode(var stage: TurnStage, var arrangement: NowPlayingLayout) :
    Modifier.Node(), LayoutModifierNode, SemanticsModifierNode, ObserverModifierNode {
    private var hidden = false

    fun update(stage: TurnStage, arrangement: NowPlayingLayout) {
        if (stage !== this.stage && isAttached) {
            this.stage.detach(this)
            stage.attach(this)
        }
        this.stage = stage
        this.arrangement = arrangement
        if (isAttached) followRole()
    }

    override fun onAttach() {
        stage.attach(this)
        followRole()
    }

    override fun onDetach() = stage.detach(this)

    override fun onObservedReadsChanged() = followRole()

    private fun followRole() {
        var now = false
        observeReads { now = stage.roles.role(arrangement) == ArrangementRole.Hidden }
        if (now != hidden) {
            hidden = now
            invalidateSemantics()
        }
    }

    override val shouldClearDescendantSemantics: Boolean get() = hidden

    override fun SemanticsPropertyReceiver.applySemantics() = Unit

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { stage.applyArrangementLayer(this, arrangement) }
        }
    }
}
