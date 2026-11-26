package org.stypox.dicio.ui.assistant

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * Shapes in the style of Material 3 Expressive, built with the same graphics-shapes library. The
 * Material 3 version Dicio uses does not expose `MaterialShapes` yet. All are normalized to the
 * unit square, so they can be morphed into each other and scaled to any size.
 */
object OverlayShapes {
    val circle = RoundedPolygon.circle(numVertices = 10).normalized()

    val cookie9 = RoundedPolygon.star(
        numVerticesPerRadius = 9,
        innerRadius = 0.8f,
        rounding = CornerRounding(0.5f),
    ).normalized()

    val softBurst = RoundedPolygon.star(
        numVerticesPerRadius = 10,
        innerRadius = 0.65f,
        rounding = CornerRounding(0.085f, smoothing = 0.6f),
    ).normalized()

    val sunny = RoundedPolygon.star(
        numVerticesPerRadius = 8,
        innerRadius = 0.8f,
        rounding = CornerRounding(0.15f),
    ).normalized()

    val pentagon = RoundedPolygon(numVertices = 5, rounding = CornerRounding(0.3f)).normalized()

    val cookie4 = RoundedPolygon.star(
        numVerticesPerRadius = 4,
        innerRadius = 0.7f,
        rounding = CornerRounding(0.45f),
    ).normalized()

    /** The shapes the orb cycles through while thinking, like the expressive loading indicator. */
    val thinkingSequence = listOf(softBurst, cookie9, pentagon, cookie4, sunny)
}

/** A [Shape] for clipping and backgrounds, from a normalized [RoundedPolygon]. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath().asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
