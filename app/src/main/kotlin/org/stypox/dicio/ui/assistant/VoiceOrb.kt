package org.stypox.dicio.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay

private const val THINKING_SHAPE_MILLIS = 650L

/**
 * The voice orb. It breathes while idle, swells with the microphone level while listening, keeps
 * morphing through shapes while thinking (like the expressive loading indicator) and settles into
 * a sunny shape when the answer arrives. Every shape change is a spring-driven [Morph].
 */
@Composable
fun VoiceOrb(
    mode: OverlayMode,
    /** The smoothed microphone level, 0 when not listening. */
    micLevel: Float,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val colors = MaterialTheme.colorScheme

    var thinkingIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode) {
        if (mode == OverlayMode.THINKING) {
            while (true) {
                delay(THINKING_SHAPE_MILLIS)
                thinkingIndex = (thinkingIndex + 1) % OverlayShapes.thinkingSequence.size
            }
        }
    }

    val target: RoundedPolygon = when (mode) {
        OverlayMode.IDLE -> OverlayShapes.circle
        OverlayMode.LISTENING -> OverlayShapes.cookie9
        OverlayMode.THINKING -> OverlayShapes.thinkingSequence[thinkingIndex]
        OverlayMode.ANSWERING -> OverlayShapes.sunny
    }

    // morph from whatever shape was showing to the new one, with a bouncy spring
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target !== to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
    }
    val morph = remember(from, to) { Morph(from, to) }

    val infinite = rememberInfiniteTransition(label = "orb")
    val breath by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "spin",
    )
    // thinking turns faster, every other mode slowly drifts
    val spinSpeed by animateFloatAsState(
        targetValue = when (mode) {
            OverlayMode.IDLE -> 0f
            OverlayMode.THINKING -> 4f
            else -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "spinSpeed",
    )
    // already smoothed by the caller, and 0 when not listening
    val level = micLevel
    val scale = when (mode) {
        OverlayMode.IDLE -> breath
        OverlayMode.LISTENING -> 0.92f + level * 0.32f
        OverlayMode.THINKING -> 0.86f
        OverlayMode.ANSWERING -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )

    Canvas(modifier = modifier.size(size * 1.5f)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val orbRadius = size.toPx() / 2
        val haloRadius = orbRadius * (1.2f + level * 0.55f)

        // soft halo that grows with the voice
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = 0.26f + level * 0.3f), Color.Transparent),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )

        val path = morph.toPath(progress.value).asComposePath()
        val side = orbRadius * 2 * animatedScale
        path.transform(Matrix().apply {
            translate(center.x, center.y)
            scale(side, side)
            translate(-0.5f, -0.5f)
        })
        rotate((spin * spinSpeed) % 360f, center) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    listOf(colors.tertiary, colors.primary, colors.primaryContainer),
                    start = Offset(center.x - side / 2, center.y - side / 2),
                    end = Offset(center.x + side / 2, center.y + side / 2),
                ),
            )
        }
        // a light spot near the top gives the orb some depth; it doesn't rotate with the shape
        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
                center = Offset(center.x - side * 0.18f, center.y - side * 0.22f),
                radius = side * 0.6f,
            ),
        )
    }
}
