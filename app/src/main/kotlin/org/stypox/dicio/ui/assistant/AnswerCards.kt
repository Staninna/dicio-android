package org.stypox.dicio.ui.assistant

import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.skills.media.MediaOutput
import org.stypox.dicio.skills.media.MediaSkill
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.ConfirmedCallOutput
import org.stypox.dicio.skills.timer.TimerOutput

/**
 * The answer card. Timers, media and calls get their own rich card; every other skill shows its
 * usual graphical output inside the same card.
 *
 * @param onInteract called when the user touches a control, to postpone the automatic close
 */
@Composable
fun AnswerCard(output: SkillOutput, ctx: SkillContext, onInteract: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        // explicit, since a translucent color has no matching content color in the scheme
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
            when {
                output is TimerOutput.Set -> TimerCard(output, ctx)
                output is MediaOutput && output.performedAction != null ->
                    MediaCard(output, ctx, onInteract)
                output is ConfirmCallOutput -> CallCard(output.name, output.number)
                output is ConfirmedCallOutput && output.number != null ->
                    CallCard(null, output.number)
                else -> output.GraphicalOutput(ctx)
            }
        }
    }
}

@Composable
private fun TimerCard(output: TimerOutput.Set, ctx: SkillContext) {
    val remaining = output.lastTickMillis.longValue.coerceAtLeast(0)
    val fraction by animateFloatAsState(
        targetValue = if (output.milliseconds > 0) remaining.toFloat() / output.milliseconds else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "timer",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(112.dp),
                strokeWidth = 8.dp,
                strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            Text(
                text = formatCountdown(remaining),
                // tabular digits, so the text doesn't jitter while counting down
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = output.name?.takeIf { it.isNotBlank() } ?: output.getSpeechOutput(ctx),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis + 999) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun MediaCard(output: MediaOutput, ctx: SkillContext, onInteract: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember { ContextCompat.getSystemService(context, AudioManager::class.java) }
    var playing by remember { mutableStateOf(audioManager?.isMusicActive == true) }
    LaunchedEffect(Unit) {
        // isMusicActive changes a moment after the media key is handled, keep it fresh
        while (true) {
            playing = audioManager?.isMusicActive == true
            delay(400)
        }
    }

    // the play button morphs from a circle (paused) to a rounded square (playing)
    val playButtonCorner by animateIntAsState(
        targetValue = if (playing) 28 else 50,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "playCorner",
    )

    fun press(key: Int) {
        MediaSkill.dispatchMediaKey(context, key)
        onInteract()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = output.getSpeechOutput(ctx),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledTonalIconButton(
                onClick = { press(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.assistant_overlay_media_previous))
            }
            FilledIconButton(
                onClick = {
                    press(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
                },
                shape = RoundedCornerShape(playButtonCorner),
                modifier = Modifier.size(80.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.assistant_overlay_media_play_pause),
                    modifier = Modifier.size(36.dp),
                )
            }
            FilledTonalIconButton(
                onClick = { press(KeyEvent.KEYCODE_MEDIA_NEXT) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.assistant_overlay_media_next))
            }
        }
    }
}

@Composable
private fun CallCard(name: String?, number: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .background(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    PolygonShape(OverlayShapes.cookie9),
                ),
        ) {
            val initial = name?.trim()?.firstOrNull()?.uppercaseChar()
            if (initial != null) {
                Text(
                    text = initial.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: number,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            if (name != null) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Call,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
