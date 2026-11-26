package org.stypox.dicio.ui.assistant

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.stypox.dicio.io.assistant.AssistantSession
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.ui.home.QuestionAnswer

private const val ANSWER_CLOSE_DELAY_MILLIS = 4000L
private const val IDLE_CLOSE_DELAY_MILLIS = 3000L
private const val NEVER_STARTED_CLOSE_DELAY_MILLIS = 8000L

@Composable
fun AssistantOverlay(session: AssistantSession) {
    val showRequest by session.showRequest.collectAsState()
    val request = showRequest ?: return

    val state by remember(request) {
        combine(
            session.sttInputDevice.uiState,
            session.skillEvaluator.state,
            AssistantSession.debugState,
        ) { stt, log, debug ->
            debug ?: overlayState(stt, log, request.answersAtShow)
        }
    }.collectAsState(initial = OverlayState(OverlayMode.IDLE, listOf(), null))
    val debugging by AssistantSession.debugState.collectAsState()
    val micLevel by WakeService.micLevel.collectAsState()
    val closing by session.closing.collectAsState()

    OverlayTheme {
        // key on the request so every show starts from a closed panel and springs up again
        key(request.id) {
            OverlayContent(
                session = session,
                state = state,
                micLevel = micLevel,
                fromWakeWord = request.fromWakeWord,
                autoClose = debugging == null,
                closing = closing,
            )
        }
    }
}

@Composable
private fun OverlayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        // the wallpaper colors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun OverlayContent(
    session: AssistantSession,
    state: OverlayState,
    micLevel: Float,
    fromWakeWord: Boolean,
    autoClose: Boolean,
    closing: Boolean,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    // 0 = closed (panel shrunk below the screen), 1 = open; driven by springs only
    val openness = remember { Animatable(0f) }
    val dragOffset = remember { Animatable(0f) }
    var touches by remember { mutableIntStateOf(0) }
    var everActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (fromWakeWord) {
            view.haptic(wake = true)
        }
    }

    // one effect drives both directions, so the open and close springs never cancel each other
    LaunchedEffect(closing) {
        if (closing) {
            openness.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
            session.onCloseAnimationDone()
        } else {
            openness.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    // haptic tick when an answer arrives: a confirm for success, a reject for errors
    var lastAnswer by remember { mutableStateOf<QuestionAnswer?>(null) }
    LaunchedEffect(state.answer) {
        val answer = state.answer
        if (answer != null && answer !== lastAnswer) {
            val failed = answer.answer is ErrorSkillOutput ||
                    answer.answer is MissingPermissionsSkillOutput
            view.haptic(wake = false, failed = failed)
        }
        lastAnswer = answer
    }

    LaunchedEffect(state.mode) {
        if (state.mode != OverlayMode.IDLE) {
            everActive = true
        }
    }

    // close by itself a few seconds after the answer (once speech is done), or after nothing
    // was said; touching a control restarts the wait
    LaunchedEffect(state.mode, state.answer, touches, autoClose, closing) {
        if (!autoClose || closing) {
            return@LaunchedEffect
        }
        when (state.mode) {
            OverlayMode.ANSWERING -> {
                while (session.isSpeaking()) {
                    delay(200)
                }
                delay(ANSWER_CLOSE_DELAY_MILLIS)
                session.requestClose(byUser = false)
            }
            OverlayMode.IDLE -> {
                delay(if (everActive) IDLE_CLOSE_DELAY_MILLIS else NEVER_STARTED_CLOSE_DELAY_MILLIS)
                session.requestClose(byUser = false)
            }
            else -> {}
        }
    }

    val glow by animateFloatAsState(
        targetValue = if (state.mode == OverlayMode.LISTENING) openness.value else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // dim the app behind; tapping it closes the overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (if (dark) 0.42f else 0.28f) * openness.value.coerceIn(0f, 1f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { session.requestClose(byUser = true) }
        )

        EdgeGlow(intensity = glow, micLevel = micLevel, modifier = Modifier.fillMaxSize())

        val maxPanelHeight = maxHeight * 0.85f
        val dismissThreshold = with(density) { 96.dp.toPx() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .graphicsLayer {
                    val open = openness.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = 0.82f + 0.18f * open
                    scaleY = 0.82f + 0.18f * open
                    translationY = (1f - open) * 220.dp.toPx() + dragOffset.value
                    alpha = open.coerceIn(0f, 1f)
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            // follows the finger downwards, resists upwards
                            val next = dragOffset.value + if (delta < 0) delta * 0.2f else delta
                            dragOffset.snapTo(next.coerceAtLeast(-24f))
                        }
                    },
                    onDragStopped = { velocity ->
                        if (dragOffset.value > dismissThreshold || velocity > 1800f) {
                            session.requestClose(byUser = true)
                        } else {
                            dragOffset.animateTo(0f, spring(dampingRatio = 0.6f))
                        }
                    },
                )
        ) {
            Panel(session, state, micLevel, onInteract = { touches++ })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panel(
    session: AssistantSession,
    state: OverlayState,
    micLevel: Float,
    onInteract: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(36.dp)
    val scroll = rememberScrollState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = colors.primary, spotColor = colors.primary)
            .background(
                Brush.verticalGradient(
                    // opaque: a window can't blur just the area behind this panel, and app
                    // content showing through unblurred looks muddy; a hint of the primary
                    // color tints the top instead
                    listOf(
                        lerp(colors.surfaceContainerHigh, colors.primaryContainer, 0.18f),
                        colors.surfaceContainer,
                    )
                ),
                shape,
            )
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), shape)
            // swallow taps so they don't reach the scrim behind and close the overlay
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            // scroll only when the content overflows (e.g. landscape); otherwise a vertical drag
            // must reach the panel's swipe-down gesture
            .verticalScroll(scroll, enabled = scroll.canScrollForward || scroll.canScrollBackward)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        VoiceOrb(
            mode = state.mode,
            micLevel = micLevel,
            size = 88.dp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // tap the orb to listen again, or to stop listening
                session.sttInputDevice.onClick(session.skillEvaluator::processInputEvent)
                onInteract()
            },
        )

        LiveWords(words = state.words, emphasized = state.mode != OverlayMode.ANSWERING)

        AnimatedVisibility(
            visible = state.answer != null,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
                    expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) +
                    scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f) +
                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) +
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        ) {
            state.answer?.let { answer ->
                Column {
                    Spacer(modifier = Modifier.size(16.dp))
                    key(answer) {
                        AnswerCard(answer.answer, session.skillContext, onInteract)
                    }
                }
            }
        }
    }
}

/**
 * The user's words in large expressive type. Each word fades and rises into place with a spring
 * when it first appears, so speech builds up word by word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveWords(words: List<String>, emphasized: Boolean) {
    val style = if (emphasized) {
        MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium)
    } else {
        MaterialTheme.typography.titleLarge
    }
    val color = if (emphasized) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    AnimatedVisibility(
        visible = words.isNotEmpty(),
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            words.forEachIndexed { index, word ->
                key(index) {
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
                    }
                    Text(
                        text = word,
                        style = style,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            alpha = appear.value.coerceIn(0f, 1f)
                            translationY = (1f - appear.value) * 14.dp.toPx()
                        },
                    )
                }
            }
        }
    }
}

private fun View.haptic(wake: Boolean, failed: Boolean = false) {
    val constant = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wake -> HapticFeedbackConstants.GESTURE_START
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && failed -> HapticFeedbackConstants.REJECT
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    }
    performHapticFeedback(constant)
}
