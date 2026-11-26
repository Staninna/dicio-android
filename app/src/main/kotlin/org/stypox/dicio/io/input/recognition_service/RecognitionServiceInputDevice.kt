package org.stypox.dicio.io.input.recognition_service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import java.util.Locale

/**
 * Speech recognition through Android's [SpeechRecognizer] API, bound to a speech recognition
 * service of another app. Parakeetype is preferred when installed (its Parakeet model is much
 * more accurate than Vosk, and it keeps the model loaded between sessions), else the first other
 * recognition service found. Dicio's own [RecognitionService] is never used, which would loop.
 *
 * With Parakeetype on Android 13+ Dicio records the microphone itself and hands the audio to the
 * service through a pipe ([RecognizerIntent.EXTRA_AUDIO_SOURCE]). Dicio's wake word service keeps
 * recording while the STT listens, and Android silences the recording of a second app while
 * another app records (or the other app may not be allowed to record from the background at all);
 * two recordings of the same app both get the audio.
 *
 * The [SpeechRecognizer] lives on the main thread and is reused across sessions, so the service
 * stays bound and warm.
 */
class RecognitionServiceInputDevice(
    private val context: Context,
    localeManager: LocaleManager,
) : SttInputDevice {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)

    private var locale: Locale
    private var service: ComponentName? = null
    private var recognizer: SpeechRecognizer? = null
    private var eventListener: ((InputEvent) -> Unit)? = null
    private var microphone: PipedMicrophone? = null

    private val _uiState = MutableStateFlow<SttState>(SttState.NotInitialized)
    override val uiState: StateFlow<SttState> = _uiState

    init {
        val (firstLocale, nextLocaleFlow) = localeManager.locale.distinctUntilChangedBlockingFirst()
        locale = firstLocale
        refreshAvailability()
        scope.launch {
            nextLocaleFlow.collect { newLocale ->
                withContext(Dispatchers.Main) { locale = newLocale }
            }
        }
    }

    /** Looks for a recognition service again: the user may have installed one meanwhile. */
    private fun refreshAvailability() {
        service = findRecognitionService(context)
        if (_uiState.value == SttState.NotAvailable || _uiState.value == SttState.NotInitialized ||
            _uiState.value is SttState.ErrorLoading
        ) {
            _uiState.value = if (service == null) SttState.NotAvailable else SttState.Loaded
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (thenStartListeningEventListener == null) {
            mainHandler.post { refreshAvailability() }
            return false
        }
        mainHandler.post { startListening(thenStartListeningEventListener) }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        mainHandler.post {
            when (_uiState.value) {
                SttState.Listening, is SttState.Loading, SttState.WaitingForResult -> stopOnMain()
                else -> startListening(eventListener)
            }
        }
    }

    override fun stopListening() {
        mainHandler.post { stopOnMain() }
    }

    override suspend fun destroy() {
        scope.cancel()
        withContext(Dispatchers.Main) {
            val listener = eventListener
            eventListener = null
            microphone?.stop()
            microphone = null
            recognizer?.destroy()
            recognizer = null
            if (listener != null) listener(InputEvent.None)
        }
    }

    private fun stopOnMain() {
        // stop the input; the service then delivers the result of what was said so far
        microphone?.stop()
        recognizer?.stopListening()
    }

    /** Main thread only. */
    private fun startListening(listener: (InputEvent) -> Unit) {
        refreshAvailability()
        val component = service ?: run {
            listener(InputEvent.None)
            return
        }
        if (eventListener != null) {
            // already listening: the new listener takes over
            eventListener = listener
            return
        }

        val sr = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(context, component).also {
                it.setRecognitionListener(Listener())
                recognizer = it
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not create the speech recognizer for $component", e)
            _uiState.value = SttState.ErrorLoading(e)
            listener(InputEvent.Error(e))
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Commands are short: end the session soon after the user stops talking
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MILLIS)
        }

        val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            component.packageName == PARAKEETYPE_PACKAGE
        ) {
            try {
                PipedMicrophone.start()
            } catch (e: Throwable) {
                // e.g. the microphone could not be opened: let the service record instead
                Log.w(TAG, "Could not record for the recognition service, it records itself", e)
                null
            }
        } else {
            null
        }
        if (mic != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, mic.readSide)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PipedMicrophone.SAMPLE_RATE)
        }

        eventListener = listener
        microphone = mic
        _uiState.value = SttState.Loading(thenStartListening = true)
        try {
            sr.startListening(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not start listening", e)
            finish(InputEvent.Error(e))
        } finally {
            // the service has its own copy of the descriptor now
            mic?.closeReadSide()
        }
    }

    /** Main thread only. Ends the session and reports [event] to its listener. */
    private fun finish(event: InputEvent, recreateRecognizer: Boolean = false) {
        val listener = eventListener
        eventListener = null
        microphone?.stop()
        microphone = null
        if (recreateRecognizer) {
            recognizer?.destroy()
            recognizer = null
        }
        _uiState.value = if (service == null) SttState.NotAvailable else SttState.Loaded
        listener?.invoke(event)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (eventListener != null) _uiState.value = SttState.Listening
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // the audio is complete, the service now computes the result
            microphone?.stop()
            if (eventListener != null) _uiState.value = SttState.WaitingForResult
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error $error (${errorName(error)})")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    finish(InputEvent.None)
                else -> finish(
                    InputEvent.Error(RecognitionServiceException(error, service)),
                    // the binding may be broken: bind again next time
                    recreateRecognizer = error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                )
            }
        }

        override fun onResults(results: Bundle?) {
            val utterances = resultUtterances(results)
            finish(if (utterances.isEmpty()) InputEvent.None else InputEvent.Final(utterances))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
            eventListener?.invoke(InputEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private val TAG = RecognitionServiceInputDevice::class.simpleName

        const val PARAKEETYPE_PACKAGE = "org.schabi.parakeetype"

        /**
         * Silence after speech that ends a session. Short, since commands are short: the user
         * waits for this long after every command before anything happens.
         */
        const val END_OF_SPEECH_SILENCE_MILLIS = 700L

        /**
         * Parakeetype's recognition service if it is installed, else the first speech recognition
         * service of another app, else `null`.
         */
        fun findRecognitionService(context: Context): ComponentName? {
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
                .filter { it.packageName != context.packageName }
                .map { ComponentName(it.packageName, it.name) }
            return services.firstOrNull { it.packageName == PARAKEETYPE_PACKAGE }
                ?: services.firstOrNull()
        }

        internal fun resultUtterances(results: Bundle?): List<Pair<String, Float>> {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return listOf()
            val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            return texts.mapIndexedNotNull { i, text ->
                if (text.isBlank()) return@mapIndexedNotNull null
                // -1 (or missing) means the service did not compute a confidence
                val confidence = confidences?.getOrNull(i)?.takeIf { it >= 0f } ?: 1.0f
                Pair(text, confidence)
            }
        }
    }
}

/** An error reported by the recognition service, with a readable message. */
class RecognitionServiceException(val error: Int, service: ComponentName?) :
    Exception("Speech recognition failed: ${errorName(error)}" +
            (service?.let { " (${it.packageName})" } ?: ""))

internal fun errorName(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
    SpeechRecognizer.ERROR_NETWORK -> "network error"
    SpeechRecognizer.ERROR_AUDIO -> "the microphone could not be used, maybe another app is using it"
    SpeechRecognizer.ERROR_SERVER -> "the recognition service failed"
    SpeechRecognizer.ERROR_CLIENT -> "client error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing was said"
    SpeechRecognizer.ERROR_NO_MATCH -> "nothing was understood"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognition service is busy"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "the recognition service is missing a permission"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "the recognition service stopped"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "the language is not supported"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "the language is not available, maybe no model is installed in the recognition app"
    else -> "error $error"
}
