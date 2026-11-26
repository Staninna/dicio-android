package org.stypox.dicio.skills.fallback.local_ai

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.eval.StreamingSkillOutput
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.io.local_ai.LocalAiClient
import org.stypox.dicio.io.local_ai.LocalAiException
import org.stypox.dicio.io.local_ai.LocalAiException.Kind
import org.stypox.dicio.io.local_ai.LocalAiProtocol
import org.stypox.dicio.io.local_ai.LocalAiProtocol.Message
import org.stypox.dicio.io.local_ai.SentenceSplitter

/** The answer of the local language model, shown while it is written and spoken per sentence. */
class LocalAiOutput(
    private val context: Context,
    private val client: LocalAiClient,
    private val messages: List<Message>,
) : StreamingSkillOutput {
    val createdAt = System.currentTimeMillis()

    private val _answer = MutableStateFlow("")
    private val _error = MutableStateFlow<LocalAiException?>(null)
    private val _isProducing = MutableStateFlow(true)
    override val isProducing: StateFlow<Boolean> = _isProducing

    /** The messages of this exchange (without the system prompt), for a follow-up question. */
    fun conversation(): List<Message> {
        val answer = _answer.value
        if (answer.isBlank()) return listOf()
        return messages.filter { it.role != "system" } + Message("assistant", answer)
    }

    override suspend fun produce(speak: suspend (String) -> Unit) {
        val splitter = SentenceSplitter()
        try {
            client.chat(messages, MAX_TOKENS) { delta ->
                _answer.value += delta
                for (sentence in splitter.add(delta)) speak(speakable(sentence))
            }
            splitter.flush()?.let { speak(speakable(it)) }
            if (_answer.value.isBlank()) {
                _error.value = LocalAiException(Kind.FAILED, "Empty answer")
                speak(context.getString(R.string.local_ai_empty_answer))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The local AI could not answer", e)
            val error = e as? LocalAiException ?: LocalAiException(Kind.FAILED, e.message ?: "", e)
            _error.value = error
            // what was written before the error has been spoken already
            speak(context.getString(errorMessage(error.kind)))
        } finally {
            _isProducing.value = false
        }
    }

    override fun getSpeechOutput(ctx: SkillContext): String =
        _error.value?.let { ctx.android.getString(errorMessage(it.kind)) } ?: _answer.value

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val answer by _answer.collectAsState()
        val error by _error.collectAsState()
        val producing by _isProducing.collectAsState()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (answer.isNotBlank()) {
                Subtitle(text = answer)
            } else if (producing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            error?.let { e ->
                Headline(text = stringResource(errorMessage(e.kind)))
                e.message?.takeIf { it.isNotBlank() }?.let { Body(text = it) }
                if (e.kind in OPEN_SERVER_KINDS) {
                    val androidContext = LocalContext.current
                    val launch = androidContext.packageManager
                        .getLaunchIntentForPackage(LocalAiProtocol.SERVER_PACKAGE)
                    if (launch != null) {
                        ElevatedButton(onClick = { androidContext.startActivity(launch) }) {
                            Text(text = stringResource(R.string.local_ai_open_server))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val TAG = LocalAiOutput::class.simpleName
        /** Enough for three spoken sentences; a model that keeps talking stops here. */
        private const val MAX_TOKENS = 256
        private val OPEN_SERVER_KINDS = setOf(Kind.NOT_RUNNING, Kind.NO_MODEL, Kind.BUSY)
        private val MARKDOWN = Regex("""[*#_`]+""")

        /** Drops markdown marks that text-to-speech would read out. */
        internal fun speakable(sentence: String) = sentence.replace(MARKDOWN, "").trim()

        fun errorMessage(kind: Kind): Int = when (kind) {
            Kind.NOT_PAIRED, Kind.UNAUTHORIZED -> R.string.local_ai_error_pair_again
            Kind.NOT_INSTALLED -> R.string.local_ai_error_not_installed
            Kind.NOT_RUNNING -> R.string.local_ai_error_not_running
            Kind.BUSY -> R.string.local_ai_error_busy
            Kind.NO_MODEL -> R.string.local_ai_error_no_model
            Kind.TOO_LONG -> R.string.local_ai_error_too_long
            Kind.FAILED -> R.string.local_ai_error_failed
        }
    }
}
