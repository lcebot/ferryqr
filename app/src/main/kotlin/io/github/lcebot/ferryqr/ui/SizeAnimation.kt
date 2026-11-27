package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.max
import kotlinx.coroutines.launch

/**
 * Reports the size of what it holds as it changes on [spec], which may be one of the theme's
 * spatial springs, while what it holds is measured and drawn at its own size from the start.
 *
 * A spring overshoots: growing, it runs past the new size and back, and shrinking, below it and
 * back. Running past is the spring's bounce, but below the size of what it holds would cut that
 * off, or let what follows overlap it, for a moment. So the size never goes below the new one once
 * it has got there, nor at all on the way down; growing, it bounces only outward.
 *
 * Unlike animateContentSize, it does not clip what it holds: a [clipToBounds][androidx.compose.ui.draw.clipToBounds]
 * before it hides what has not yet been reached while growing, where that is wanted. What it holds
 * sits at [alignment] within the size reported, so content centred in a container stays centred
 * while the container takes its new size.
 */
internal fun Modifier.animateSize(spec: FiniteAnimationSpec<IntSize>, alignment: Alignment = Alignment.TopStart): Modifier =
    this.then(SizeAnimationElement(spec, alignment))

private class SizeAnimationElement(
    val spec: FiniteAnimationSpec<IntSize>,
    val alignment: Alignment,
) : ModifierNodeElement<SizeAnimationNode>() {
    override fun create() = SizeAnimationNode(spec, alignment)

    override fun update(node: SizeAnimationNode) {
        node.spec = spec
        node.alignment = alignment
    }

    override fun equals(other: Any?) = other is SizeAnimationElement && other.spec == spec && other.alignment == alignment

    override fun hashCode() = 31 * spec.hashCode() + alignment.hashCode()
}

private class SizeAnimationNode(
    var spec: FiniteAnimationSpec<IntSize>,
    var alignment: Alignment,
) : Modifier.Node(), LayoutModifierNode {
    private var size: Animatable<IntSize, AnimationVector2D>? = null
    private var target = IntSize.Zero

    // Whether the size has reached the target in each direction, from where on it stays at least as large.
    private var widthReached = true
    private var heightReached = true

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val goal = IntSize(placeable.width, placeable.height)
        val shown = size ?: Animatable(goal, IntSize.VectorConverter).also {
            size = it
            target = goal
        }
        if (goal != target) {
            val from = shown.value
            target = goal
            widthReached = from.width >= goal.width
            heightReached = from.height >= goal.height
            coroutineScope.launch { shown.animateTo(goal, spec) }
        }
        val now = shown.value
        if (now.width >= target.width) widthReached = true
        if (now.height >= target.height) heightReached = true
        val width = if (widthReached) max(now.width, target.width) else now.width
        val height = if (heightReached) max(now.height, target.height) else now.height
        val shownWidth = constraints.constrainWidth(width)
        val shownHeight = constraints.constrainHeight(height)
        return layout(shownWidth, shownHeight) {
            placeable.place(alignment.align(goal, IntSize(shownWidth, shownHeight), layoutDirection))
        }
    }
}
