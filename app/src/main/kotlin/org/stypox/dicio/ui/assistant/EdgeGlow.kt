package org.stypox.dicio.ui.assistant

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * A soft gradient glow that wraps the screen edges, in the wallpaper colors, slowly turning.
 * [intensity] fades it in and out (0 = hidden) and [micLevel] makes it brighter and wider.
 */
@Composable
fun EdgeGlow(intensity: Float, micLevel: Float, modifier: Modifier = Modifier) {
    if (intensity <= 0.001f) {
        return
    }

    val colors = MaterialTheme.colorScheme
    val gradient = listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)
    val density = LocalDensity.current
    val view = LocalView.current
    val cornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
    } else {
        null
    } ?: with(density) { 36.dp.toPx() }

    val turn by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "turn",
    )

    Canvas(
        modifier = modifier.blur(10.dp, BlurredEdgeTreatment.Unbounded)
    ) {
        val base = 10.dp.toPx() + micLevel * 14.dp.toPx()
        // a few strokes from wide and faint to narrow and bright give a soft edge also where
        // Modifier.blur is not supported (before Android 12)
        val layers = listOf(3.2f to 0.35f, 2.0f to 0.55f, 1.0f to 0.95f)
        for ((widthFactor, alpha) in layers) {
            val width = base * widthFactor
            // inset by half the stroke, so the whole stroke is on screen
            val inset = width / 2
            drawRoundRect(
                brush = Brush.sweepGradient(
                    gradient.map { it.copy(alpha = alpha * intensity * (0.8f + micLevel * 0.2f)) },
                    center = rotatedCenter(center, turn, size),
                ),
                topLeft = Offset(inset, inset),
                size = Size(size.width - width, size.height - width),
                cornerRadius = CornerRadius((cornerRadius - inset).coerceAtLeast(0f)),
                style = Stroke(width = width),
            )
        }
    }
}

/**
 * Moving the gradient's center around the screen center makes the colors flow along the edges,
 * which reads as a turning glow without rotating the rectangle itself.
 */
private fun rotatedCenter(center: Offset, degrees: Float, size: Size): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    val radius = minOf(size.width, size.height) * 0.18f
    return Offset(
        center.x + (radius * kotlin.math.cos(radians)).toFloat(),
        center.y + (radius * kotlin.math.sin(radians)).toFloat(),
    )
}
