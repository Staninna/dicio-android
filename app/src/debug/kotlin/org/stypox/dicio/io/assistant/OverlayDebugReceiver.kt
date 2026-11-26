package org.stypox.dicio.io.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.ui.assistant.OverlayMode
import org.stypox.dicio.ui.assistant.OverlayState
import javax.inject.Inject

/**
 * Debug builds only: drives the assistant overlay from adb, to test every state without speaking.
 * Dicio must be the default digital assistant. `$ID` is the debug application id.
 *
 *     adb shell am broadcast -n $ID/org.stypox.dicio.io.assistant.OverlayDebugReceiver --es step show
 *     ... --es step listening --es text "set a timer for"   (fake listening state with these words)
 *     ... --es step thinking --es text "what time is it"    (fake thinking state)
 *     ... --es step idle                                    (fake idle state, breathing orb)
 *     ... --es step say --es text "set a timer for 5 minutes"  (real skill evaluation)
 *     ... --es step release                                 (back to the real state, auto-close on)
 *     ... --es step hide
 *
 * While a fake state is shown the overlay does not close by itself.
 */
@AndroidEntryPoint
class OverlayDebugReceiver : BroadcastReceiver() {
    @Inject
    lateinit var skillEvaluator: SkillEvaluator

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        val words = text.split(' ').filter { it.isNotBlank() }
        when (val step = intent.getStringExtra("step")) {
            "show" -> {
                AssistantSession.debugState.value = null
                if (!DicioVoiceInteractionService.showOverlay(fromWakeWord = true, debug = true)) {
                    Log.e(TAG, "Could not show the overlay: is Dicio the default assistant?")
                }
            }
            "listening" -> AssistantSession.debugState.value =
                OverlayState(OverlayMode.LISTENING, words, null)
            "thinking" -> AssistantSession.debugState.value =
                OverlayState(OverlayMode.THINKING, words, null)
            "idle" -> AssistantSession.debugState.value = OverlayState(OverlayMode.IDLE, listOf(), null)
            "say" -> {
                AssistantSession.debugState.value = null
                skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(text, 1.0f))))
            }
            "release" -> AssistantSession.debugState.value = null
            "hide" -> {
                AssistantSession.debugState.value = null
                DicioVoiceInteractionService.hideOverlay()
            }
            else -> Log.e(TAG, "Unknown step $step")
        }
    }

    companion object {
        private val TAG = OverlayDebugReceiver::class.simpleName
    }
}
