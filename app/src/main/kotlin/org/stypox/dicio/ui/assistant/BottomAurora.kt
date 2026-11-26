package org.stypox.dicio.ui.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A soft light in the wallpaper colors that rises from the bottom edge of the screen, leaving the
 * top clean. It reaches a little above [floor] when quiet and swells higher with [level] (the
 * smoothed microphone level, 0 to 1). Its colors drift slowly from side to side.
 *
 * @param intensity 0 hides it, 1 is full strength
 * @param floor the height in pixels the panel covers at the bottom; the light starts above it
 * @param pulsing makes it breathe slowly, while Dicio is thinking
 */
@Composable
fun BottomAurora(
    intensity: Float,
    level: Float,
    floor: Float,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    // the panel grows and shrinks with its content; follow it smoothly
    val smoothFloor by animateFloatAsState(
        targetValue = floor,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "floor",
    )
    val pulseDepth by animateFloatAsState(
        targetValue = if (pulsing) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "pulseDepth",
    )
    if (intensity <= 0.001f) {
        return
    }

    val colors = MaterialTheme.colorScheme
    val blobColors = listOf(colors.primary, colors.tertiary, colors.secondary)

    // one full turn of the phase is one drift cycle; sine waves of it loop without a seam
    val phase by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(DRIFT_CYCLE_MILLIS, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val reach = (smoothFloor + size.height * (0.12f + 0.2f * level.coerceIn(0f, 1f)))
            .coerceAtMost(size.height * 0.7f)
        // five slow pulses per drift cycle
        val pulse = 1f - 0.4f * pulseDepth * (0.5f + 0.5f * sin(phase * 5))
        val strength = intensity.coerceIn(0f, 1f) * pulse

        // an even floor of light, so there are never gaps between the colored blobs
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to colors.primary.copy(alpha = 0.45f * strength),
                startY = size.height - reach,
                endY = size.height,
            ),
        )

        // colored blobs centered just below the bottom edge, stretched wide, drifting sideways
        val radius = reach / 0.9f
        blobColors.forEachIndexed { i, color ->
            val drift = sin(phase + i * 2.1f) * size.width * 0.12f
            val center = Offset(size.width * (0.2f + 0.3f * i) + drift, size.height + radius * 0.1f)
            val stretch = max(1f, size.width * 0.42f / radius)
            scale(scaleX = stretch, scaleY = 1f, pivot = center) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = 0.9f * strength),
                        0.6f to color.copy(alpha = 0.4f * strength),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}

private const val DRIFT_CYCLE_MILLIS = 9000
