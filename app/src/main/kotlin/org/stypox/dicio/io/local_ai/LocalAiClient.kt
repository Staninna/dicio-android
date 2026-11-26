package org.stypox.dicio.io.local_ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.stypox.dicio.io.local_ai.LocalAiException.Kind
import org.stypox.dicio.io.local_ai.LocalAiProtocol.Message
import org.stypox.dicio.io.local_ai.LocalAiProtocol.StreamEvent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Talks to the SmolChat server on this phone (contract v3): checks that it runs, starts it when it
 * does not (§3.5), and streams chat completions.
 */
@Singleton
class LocalAiClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val pairing: LocalAiPairing,
) {
    private val healthClient = okHttpClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // The server sends a keep-alive comment every 5 s while it works, so 30 s without any byte
    // means the server is gone. The prompt may wait in the queue or for the model to load first,
    // without keep-alives: allow for that in the first read.
    private val chatClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Streams the answer to [messages], calling [onDelta] with each piece of text, and returns the
     * whole answer. Starts the server first if it is not running.
     */
    suspend fun chat(messages: List<Message>, maxTokens: Int, onDelta: suspend (String) -> Unit): String {
        val paired = pairing.pairing.value
            ?: throw LocalAiException(Kind.NOT_PAIRED, "Dicio is not paired with SmolChat")
        ensureRunning(paired)

        val request = Request.Builder()
            .url("${paired.baseUrl}/v1/chat/completions")
            .header("Authorization", "Bearer ${paired.token}")
            .header("Accept", "text/event-stream")
            .post(LocalAiProtocol.chatRequestBody(messages, maxTokens).toRequestBody(JSON))
            .build()

        return withContext(Dispatchers.IO) {
            val call = chatClient.newCall(request)
            // closing the connection cancels the generation on the server (§1.2)
            val cancelOnCancel = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
                if (it != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw LocalAiProtocol.errorFromResponse(response.code, response.body.string())
                    }
                    val source = response.body.source()
                    val answer = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when (val event = LocalAiProtocol.parseStreamLine(line)) {
                            is StreamEvent.Delta -> {
                                answer.append(event.text)
                                onDelta(event.text)
                            }
                            StreamEvent.Done -> break
                            is StreamEvent.Error -> throw LocalAiException(
                                LocalAiProtocol.errorKind(null, event.code),
                                event.message ?: event.code ?: "Generation failed",
                            )
                            StreamEvent.Nothing -> {}
                        }
                    }
                    answer.toString()
                }
            } catch (e: IOException) {
                throw LocalAiException(Kind.NOT_RUNNING, e.message ?: "Connection to SmolChat lost", e)
            } finally {
                cancelOnCancel?.dispose()
            }
        }
    }

    /** Makes sure the server answers `/health`, starting it if needed. */
    private suspend fun ensureRunning(paired: LocalAiPairing.Pairing) {
        if (health(paired) != null) return
        if (!pairing.isServerInstalled()) {
            throw LocalAiException(Kind.NOT_INSTALLED, "SmolChat is not installed")
        }
        when (val result = wake()) {
            LocalAiProtocol.WAKE_STARTED -> {}
            LocalAiProtocol.WAKE_NOT_PAIRED ->
                throw LocalAiException(Kind.UNAUTHORIZED, "SmolChat does not know Dicio anymore")
            LocalAiProtocol.WAKE_NO_MODEL ->
                throw LocalAiException(Kind.NO_MODEL, "SmolChat has no model")
            else -> throw LocalAiException(Kind.NOT_RUNNING, "SmolChat could not be started ($result)")
        }
        val started = withTimeoutOrNull(WAKE_TIMEOUT_MILLIS) {
            while (health(paired) == null) delay(HEALTH_POLL_MILLIS)
            true
        }
        if (started == null) throw LocalAiException(Kind.NOT_RUNNING, "SmolChat did not start")
    }

    /** The server's status, or `null` when it does not answer. */
    private suspend fun health(paired: LocalAiPairing.Pairing): String? = withContext(Dispatchers.IO) {
        try {
            healthClient.newCall(Request.Builder().url("${paired.baseUrl}/health").build())
                .execute().use { response ->
                    if (!response.isSuccessful) null
                    else LocalAiProtocol.parseHealthStatus(response.body.string()) ?: "unknown"
                }
        } catch (_: IOException) {
            null
        }
    }

    /** Binds the server's wake service and calls `wake()` (§3.5); returns its result code. */
    private suspend fun wake(): Int {
        val intent = Intent(LocalAiProtocol.ACTION_WAKE).setPackage(LocalAiProtocol.SERVER_PACKAGE)
        var connection: ServiceConnection? = null
        try {
            val binder = withTimeoutOrNull(WAKE_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {}

                        override fun onNullBinding(name: ComponentName?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    connection = conn
                    val bound = try {
                        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Could not bind the wake service", e)
                        false
                    }
                    if (!bound && cont.isActive) cont.resume(null)
                }
            } ?: return LocalAiProtocol.WAKE_FAILED

            return withContext(Dispatchers.IO) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(LocalAiProtocol.WAKE_INTERFACE)
                    binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                    reply.readException()
                    reply.readInt()
                } catch (e: Exception) {
                    Log.w(TAG, "wake() failed", e)
                    LocalAiProtocol.WAKE_FAILED
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    companion object {
        private val TAG = LocalAiClient::class.simpleName
        private val JSON = "application/json".toMediaType()
        private const val WAKE_TIMEOUT_MILLIS = 10_000L
        private const val HEALTH_POLL_MILLIS = 500L
    }
}
