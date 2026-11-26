package org.stypox.dicio.io.assistant

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * The system binds this service while Dicio is the default digital assistant. It lets Dicio show
 * [AssistantSession] over whatever app is open, which is how the wake word opens the overlay
 * without having to start an activity from the background.
 */
class DicioVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        current = this
    }

    override fun onShutdown() {
        if (current === this) {
            current = null
        }
        super.onShutdown()
    }

    companion object {
        private val TAG = DicioVoiceInteractionService::class.simpleName
        const val EXTRA_FROM_WAKE_WORD = "org.stypox.dicio.io.assistant.FROM_WAKE_WORD"
        const val EXTRA_DEBUG = "org.stypox.dicio.io.assistant.DEBUG"

        @Volatile
        private var current: DicioVoiceInteractionService? = null

        /**
         * Shows the assistant overlay over the current app.
         *
         * @param fromWakeWord the caller already started the STT, so the overlay must not start
         * it again
         * @param debug the overlay must not touch the STT at all, see `OverlayDebugReceiver`
         * @return false if Dicio is not the default digital assistant (or Android is older than
         * 6), in which case the caller should fall back to the notification
         */
        fun showOverlay(fromWakeWord: Boolean, debug: Boolean = false): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            val service = current ?: return false
            val component = ComponentName(service, DicioVoiceInteractionService::class.java)
            if (!isActiveService(service, component)) {
                return false
            }

            return try {
                service.showSession(Bundle().apply {
                    putBoolean(EXTRA_FROM_WAKE_WORD, fromWakeWord)
                    putBoolean(EXTRA_DEBUG, debug)
                }, 0)
                true
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not show the assistant overlay", e)
                false
            }
        }

        fun hideOverlay() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AssistantSession.shown?.requestClose(byUser = true)
            }
        }
    }
}
