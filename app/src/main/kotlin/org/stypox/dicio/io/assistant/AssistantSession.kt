package org.stypox.dicio.io.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SpeechOutputDeviceWrapper
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.assistant.AssistantOverlay
import org.stypox.dicio.ui.assistant.OverlayState
import org.stypox.dicio.ui.assistant.answerCount

/**
 * One showing of the assistant overlay. The content is [AssistantOverlay], drawn in a transparent
 * full-screen window on top of the current app. Compose needs a lifecycle, saved state and view
 * model store, which a [VoiceInteractionSession] doesn't have, so this class provides them.
 *
 * Several session methods need Android 6. That's safe: the assistant services are disabled below
 * Android 6 (see `assistant_overlay_supported`), so this class is never created there.
 */
@SuppressLint("NewApi")
class AssistantSession(
    context: Context,
    val skillEvaluator: SkillEvaluator,
    val sttInputDevice: SttInputDeviceWrapper,
    private val speechOutputDevice: SpeechOutputDeviceWrapper,
    val skillContext: SkillContextInternal,
) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    /** Changes every time the overlay is shown, so that the UI restarts its enter animation. */
    data class ShowRequest(val id: Int, val fromWakeWord: Boolean, val answersAtShow: Int)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val _showRequest = MutableStateFlow<ShowRequest?>(null)
    val showRequest: StateFlow<ShowRequest?> = _showRequest

    private val _closing = MutableStateFlow(false)
    val closing: StateFlow<Boolean> = _closing

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        window.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            w.decorView.setViewTreeLifecycleOwner(this)
            w.decorView.setViewTreeSavedStateRegistryOwner(this)
            w.decorView.setViewTreeViewModelStoreOwner(this)
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setViewTreeViewModelStoreOwner(this@AssistantSession)
        setContent { AssistantOverlay(this@AssistantSession) }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val fromWakeWord = args?.getBoolean(DicioVoiceInteractionService.EXTRA_FROM_WAKE_WORD) == true
        val debug = args?.getBoolean(DicioVoiceInteractionService.EXTRA_DEBUG) == true

        // reset closing before publishing the request, or the new panel would start closing
        _closing.value = false
        _showRequest.value = ShowRequest(
            id = (_showRequest.value?.id ?: 0) + 1,
            fromWakeWord = fromWakeWord,
            answersAtShow = answerCount(skillEvaluator.state.value),
        )
        shown = this
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        if (!fromWakeWord && !debug) {
            // opened by the assist gesture or button: start listening right away, like the
            // main activity does when it receives the assist intent
            sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
        }
    }

    override fun onHide() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        if (sttInputDevice.uiState.value == SttState.Listening) {
            // the overlay is gone (closed by the user or by the system), stop listening
            sttInputDevice.stopListening()
        }
        _showRequest.value = null
        debugState.value = null
        if (shown === this) {
            shown = null
        }
        super.onHide()
    }

    override fun onBackPressed() {
        requestClose(byUser = true)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    /**
     * Starts the exit animation; the UI calls [onCloseAnimationDone] when it ends. A close by the
     * user (swipe, tap outside, back) also stops speech; an automatic close lets it finish.
     */
    fun requestClose(byUser: Boolean) {
        if (byUser) {
            speechOutputDevice.stopSpeaking()
        }
        _closing.value = true
    }

    fun onCloseAnimationDone() {
        hide()
    }

    fun isSpeaking(): Boolean = speechOutputDevice.isSpeaking

    companion object {
        /**
         * Replaces the real state in the overlay while not null. Only debug builds set it (see
         * `OverlayDebugReceiver`), to take screenshots of every state without speaking.
         */
        val debugState = MutableStateFlow<OverlayState?>(null)

        /** The session currently on screen, if any. */
        @Volatile
        var shown: AssistantSession? = null
            private set
    }
}
