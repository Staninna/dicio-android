package org.stypox.dicio.io.local_ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The parts of the SmolChat server's local LLM API (contract v3, `docs/LOCAL_LLM_API.md` in the
 * SmolChat fork) that Dicio uses, as pure functions.
 */
object LocalAiProtocol {
    const val SERVER_PACKAGE = "io.shubham0204.smollmandroid"
    const val ACTION_PAIR = "$SERVER_PACKAGE.action.PAIR"
    const val ACTION_WAKE = "$SERVER_PACKAGE.action.WAKE"
    const val WAKE_INTERFACE = "$SERVER_PACKAGE.IWake"

    const val WAKE_STARTED = 0
    const val WAKE_NOT_PAIRED = 1
    const val WAKE_NO_MODEL = 2
    const val WAKE_NOT_ALLOWED = 3
    const val WAKE_FAILED = 4

    private val json = Json { ignoreUnknownKeys = true }

    /** One message of the conversation: role is `system`, `user` or `assistant`. */
    data class Message(val role: String, val content: String)

    fun chatRequestBody(messages: List<Message>, maxTokens: Int, stream: Boolean = true): String =
        buildJsonObject {
            put("messages", buildJsonArray {
                for (m in messages) {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
            put("max_tokens", maxTokens)
            put("stream", stream)
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        }.toString()

    /** What one line of the server-sent event stream says. */
    sealed interface StreamEvent {
        data class Delta(val text: String) : StreamEvent
        data object Done : StreamEvent
        data class Error(val code: String?, val message: String?) : StreamEvent
        /** Blank lines, comments (`: keep-alive`), events without text. */
        data object Nothing : StreamEvent
    }

    fun parseStreamLine(line: String): StreamEvent {
        if (!line.startsWith("data:")) return StreamEvent.Nothing
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamEvent.Done
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            return StreamEvent.Nothing
        }
        (obj["error"] as? JsonObject)?.let { error ->
            return StreamEvent.Error(error.string("code"), error.string("message"))
        }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return StreamEvent.Nothing
        val content = (choice["delta"] as? JsonObject)?.string("content")
        return if (content.isNullOrEmpty()) StreamEvent.Nothing else StreamEvent.Delta(content)
    }

    /** The `status` of a `GET /health` answer, or `null` if it is not readable. */
    fun parseHealthStatus(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject.string("status")
    } catch (_: Exception) {
        null
    }

    /** The error of a JSON error answer (§4) with HTTP status [httpCode]. */
    fun errorFromResponse(httpCode: Int, body: String?): LocalAiException {
        val error = try {
            body?.let { json.parseToJsonElement(it).jsonObject["error"] as? JsonObject }
        } catch (_: Exception) {
            null
        }
        val code = error?.string("code")
        val message = error?.string("message")
        return LocalAiException(errorKind(httpCode, code), message ?: "HTTP $httpCode")
    }

    fun errorKind(httpCode: Int?, code: String?): LocalAiException.Kind = when {
        httpCode == 401 || code == "unauthorized" -> LocalAiException.Kind.UNAUTHORIZED
        httpCode == 429 || code == "server_busy" -> LocalAiException.Kind.BUSY
        httpCode == 503 || code == "model_not_loaded" -> LocalAiException.Kind.NO_MODEL
        code == "context_length_exceeded" -> LocalAiException.Kind.TOO_LONG
        else -> LocalAiException.Kind.FAILED
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}

/**
 * Splits streamed text into sentences, so each can be spoken as soon as it is complete instead of
 * waiting for the whole answer.
 */
class SentenceSplitter {
    private val pending = StringBuilder()

    /** Adds [text] and returns the sentences it completed. */
    fun add(text: String): List<String> {
        pending.append(text)
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            val isEnd = c == '\n' || ((c == '.' || c == '!' || c == '?') &&
                    // only an end followed by a space: not "3.5" or "e.g" in the middle of a word
                    i + 1 < pending.length && pending[i + 1].isWhitespace())
            if (isEnd) {
                val sentence = pending.substring(start, i + 1).trim()
                // very short pieces ("Dr.", "1.") stay with the next sentence
                if (sentence.length >= MIN_SENTENCE_LENGTH || c == '\n') {
                    if (sentence.isNotEmpty()) sentences.add(sentence)
                    start = i + 1
                }
            }
            i++
        }
        pending.delete(0, start)
        return sentences
    }

    /** The rest of the text, after the stream ended. */
    fun flush(): String? = pending.toString().trim().takeIf { it.isNotEmpty() }.also {
        pending.clear()
    }

    private companion object {
        const val MIN_SENTENCE_LENGTH = 12
    }
}

class LocalAiException(val kind: Kind, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Kind {
        /** Dicio is not paired with the server. */
        NOT_PAIRED,
        /** The token was revoked or the server lost its pairings: pair again. */
        UNAUTHORIZED,
        /** The server app is not installed. */
        NOT_INSTALLED,
        /** The server is not running and could not be started. */
        NOT_RUNNING,
        BUSY,
        NO_MODEL,
        TOO_LONG,
        FAILED,
    }
}
