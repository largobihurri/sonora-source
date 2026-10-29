package io.sonora.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.TranslucentOptics
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme

/** The gap between a pill's edge and a leading thumbnail [thumbnail] tall: the same above and below it. */
fun pillInset(height: Dp, thumbnail: Dp): Dp = ((height - thumbnail) / 2).coerceAtLeast(0.dp)

/**
 * Where a leading thumbnail [thumbnail] tall, with corners of [corner], starts in a capsule [height] tall, so that its
 * corners keep [pillInset] from the capsule's round end, as its top and bottom do from the straight edges. A round
 * thumbnail sits at the plain inset; a rounded square moves in until its corner arcs share the round end's centre.
 */
fun pillLeadingInset(height: Dp, thumbnail: Dp, corner: Dp): Dp {
    val inset = pillInset(height, thumbnail)
    val radius = corner.coerceIn(0.dp, thumbnail / 2)
    return maxOf(inset, height / 2 - radius)
}

/**
 * The content of a [TranslucentPill]: a row whose labels can live under the translucent surface too ([reflected]).
 */
@Stable
interface TranslucentPillScope : RowScope {
    /**
     * Marks content (a label, a glyph) whose back the translucent surface reflects: a soft copy is drawn under it,
     * where the surface blurs it, bends it and carries the colours under it, while the content stays crisp on top.
     */
    fun Modifier.reflected(): Modifier
}

/**
 * The shared translucent pill: a capsule of [TranslucentSurface] [height] tall whose content fills it, is centred and
 * owns the click. Labels marked [TranslucentPillScope.reflected] are also reflected under the surface. Pad a leading
 * thumbnail with [pillLeadingInset] through [startPadding]. [contentModifier] goes on the content row, before the
 * click (e.g. its semantics). The translucency parameters are [TranslucentSurface]'s.
 */
@Composable
fun TranslucentPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onClickLabel: String? = null,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 16.dp,
    elevation: Dp = 0.dp,
    darkTheme: Boolean = SonoraTheme.isDark,
    backdrop: TranslucentBackdrop? = LocalTranslucentBackdrop.current,
    tinted: Boolean = false,
    wash: () -> Color = { Color.Transparent },
    lightShift: () -> Float = { 0f },
    contentModifier: Modifier = Modifier,
    content: @Composable TranslucentPillScope.() -> Unit,
) {
    val reflection = remember { TranslucentReflection() }
    TranslucentSurface(
        modifier.height(height),
        shape = CapsuleShape,
        elevation = elevation,
        darkTheme = darkTheme,
        backdrop = backdrop,
        lightShift = lightShift,
        tinted = tinted,
        wash = wash,
        reflection = reflection,
    ) {
        Row(
            // Fills the surface, so the label and its touch area are centred and never hug the top edge.
            Modifier.fillMaxHeight().clip(CapsuleShape)
                .then(contentModifier)
                .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                .padding(start = startPadding, end = endPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val scope = remember(this, reflection) { PillScope(this, reflection) }
            scope.content()
        }
    }
}

/** A [TranslucentPill] showing one [label] in [style], reflected under the translucent surface. */
@Composable
fun TranslucentPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    style: TextStyle = LocalTextStyle.current,
    elevation: Dp = 0.dp,
    darkTheme: Boolean = SonoraTheme.isDark,
    backdrop: TranslucentBackdrop? = LocalTranslucentBackdrop.current,
    tinted: Boolean = false,
    wash: () -> Color = { Color.Transparent },
    lightShift: () -> Float = { 0f },
) {
    TranslucentPill(
        onClick = onClick,
        modifier = modifier,
        height = height,
        onClickLabel = label,
        elevation = elevation,
        darkTheme = darkTheme,
        backdrop = backdrop,
        tinted = tinted,
        wash = wash,
        lightShift = lightShift,
    ) {
        Text(label, Modifier.reflected(), color = LocalTranslucentContentColors.current.label, style = style, maxLines = 1)
    }
}

private class PillScope(row: RowScope, private val reflection: TranslucentReflection) : TranslucentPillScope, RowScope by row {
    override fun Modifier.reflected(): Modifier = this.then(ReflectedElement(reflection))
}

/**
 * The content a translucent surface reflects under itself ([TranslucentSurface]'s `reflection`): each piece is recorded
 * once into its own graphics layer as it draws, and the surface draws that layer again inside its backdrop, so the
 * surface's own effects (blur, lens, light) shape the copy at no extra pass.
 */
@Stable
class TranslucentReflection internal constructor() {
    private val pieces = mutableListOf<ReflectedNode>()
    private val paint = Paint()

    /** Where the translucent surface is laid out, to place each piece within it. */
    internal var surface: LayoutCoordinates? = null

    internal fun add(piece: ReflectedNode) {
        pieces += piece
    }

    internal fun remove(piece: ReflectedNode) {
        pieces -= piece
    }

    /** Draws every piece under the surface, [optics]'s reflection away from a light at [lightAngle] degrees. */
    internal fun draw(scope: DrawScope, optics: TranslucentOptics, lightAngle: Float) {
        if (optics.reflectionAlpha <= 0f || pieces.isEmpty()) return
        val surface = surface?.takeIf { it.isAttached } ?: return
        val shift = optics.reflectionOffset(lightAngle) * scope.density
        val pivot = surface.size.toSize().let { Offset(it.width / 2f, it.height / 2f) }
        paint.alpha = optics.reflectionAlpha
        paint.blendMode = if (optics.reflectionAdds) BlendMode.Plus else BlendMode.SrcOver
        for (piece in pieces) {
            val coordinates = piece.coordinates?.takeIf { it.isAttached } ?: continue
            val layer = piece.layer ?: continue
            val origin = surface.localPositionOf(coordinates, Offset.Zero)
            val bounds = Rect(origin, coordinates.size.toSize()).inflate(scope.density * BOUNDS_SLACK_DP)
            scope.withTransform({
                translate(shift.x, shift.y)
                scale(optics.reflectionScale, optics.reflectionScale, pivot)
            }) {
                drawContext.canvas.saveLayer(bounds, paint)
                translate(origin.x, origin.y) { drawLayer(layer) }
                drawContext.canvas.restore()
            }
        }
    }

    private companion object {
        /** Room around a piece for glyphs that draw slightly outside their box. */
        const val BOUNDS_SLACK_DP = 4f
    }
}

private class ReflectedElement(private val reflection: TranslucentReflection) : ModifierNodeElement<ReflectedNode>() {
    override fun create() = ReflectedNode(reflection)

    override fun update(node: ReflectedNode) = node.attachTo(reflection)

    override fun equals(other: Any?) = other is ReflectedElement && other.reflection === reflection

    override fun hashCode() = System.identityHashCode(reflection)

    override fun InspectorInfo.inspectableProperties() {
        name = "reflected"
    }
}

/** Records its content into a layer as it draws it, for [TranslucentReflection] to draw again under the surface. */
internal class ReflectedNode(private var reflection: TranslucentReflection) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    var layer: GraphicsLayer? = null
        private set
    var coordinates: LayoutCoordinates? = null
        private set

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
        reflection.add(this)
    }

    override fun onDetach() {
        reflection.remove(this)
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        coordinates = null
    }

    fun attachTo(next: TranslucentReflection) {
        if (next === reflection) return
        if (isAttached) reflection.remove(this)
        reflection = next
        if (isAttached) next.add(this)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        val recorded = layer ?: return drawContent()
        recorded.record { this@draw.drawContent() }
        drawLayer(recorded)
    }
}
