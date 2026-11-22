package com.example.ws

import com.example.model.WsClientConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WsClientRuntimeStatus(
    val serverUrl: String = StageWebSocketClient.DEFAULT_WS_CLIENT_URL,
    val state: WsClientConnectionState = WsClientConnectionState.DISCONNECTED,
    val lastError: String? = null
)

/**
 * WebSocket Client that connects to a remote WebSocket server (e.g. ws://192.168.1.230:8080/ws)
 * and transmits auto-detected key harmony commands in real time.
 */
class StageWebSocketClient(
    private val onMessageReceived: (serverUrl: String, message: String) -> String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _status = MutableStateFlow(WsClientRuntimeStatus())
    val status: StateFlow<WsClientRuntimeStatus> = _status.asStateFlow()

    private val activeWanted = AtomicBoolean(false)
    private var autoReconnect = true
    private var currentSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    fun connect(url: String, autoReconnectEnabled: Boolean) {
        val cleanUrl = url.trim()
        autoReconnect = autoReconnectEnabled
        if (cleanUrl.isBlank()) {
            _status.value = WsClientRuntimeStatus(
                serverUrl = cleanUrl,
                state = WsClientConnectionState.ERROR,
                lastError = "Server URL cannot be empty"
            )
            return
        }
        val normalizedUrl = normalizeWsUrl(cleanUrl)

        disconnectInternal(keepWanted = false)
        activeWanted.set(true)
        openSocket(normalizedUrl)
    }

    fun disconnect() {
        disconnectInternal(keepWanted = false)
        _status.value = _status.value.copy(
            state = WsClientConnectionState.DISCONNECTED,
            lastError = null
        )
    }

    fun sendText(text: String): Boolean {
        return currentSocket?.send(text) == true
    }

    private fun openSocket(url: String) {
        if (!activeWanted.get()) return
        _status.value = WsClientRuntimeStatus(
            serverUrl = url,
            state = WsClientConnectionState.CONNECTING,
            lastError = null
        )

        val request = try {
            Request.Builder().url(url).build()
        } catch (t: Throwable) {
            _status.value = WsClientRuntimeStatus(
                serverUrl = url,
                state = WsClientConnectionState.ERROR,
                lastError = t.localizedMessage ?: "Invalid WebSocket URL"
            )
            return
        }

        currentSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== currentSocket) {
                    webSocket.close(1000, "Superseded")
                    return
                }
                _status.value = WsClientRuntimeStatus(
                    serverUrl = url,
                    state = WsClientConnectionState.CONNECTED,
                    lastError = null
                )
                val hello = onMessageReceived(url, "__CONNECTED__")
                if (hello.isNotBlank()) {
                    webSocket.send(hello)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val reply = onMessageReceived(url, text)
                if (reply.isNotBlank()) {
                    webSocket.send(reply)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== currentSocket) return
                currentSocket = null
                scheduleReconnectIfNeeded(url, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== currentSocket) return
                currentSocket = null
                val httpPart = response?.let { "HTTP ${it.code} ${it.message}".trim() }
                val baseMsg = t.localizedMessage ?: t.message
                val msg = listOfNotNull(httpPart, baseMsg).distinct().joinToString(" · ")
                    .ifBlank { "Connection failed" }
                scheduleReconnectIfNeeded(url, msg)
            }
        })
    }

    private fun scheduleReconnectIfNeeded(url: String, errorMsg: String?) {
        if (!activeWanted.get()) {
            _status.value = WsClientRuntimeStatus(
                serverUrl = url,
                state = WsClientConnectionState.DISCONNECTED,
                lastError = errorMsg
            )
            return
        }

        if (!autoReconnect) {
            activeWanted.set(false)
            _status.value = WsClientRuntimeStatus(
                serverUrl = url,
                state = if (errorMsg != null) WsClientConnectionState.ERROR else WsClientConnectionState.DISCONNECTED,
                lastError = errorMsg
            )
            return
        }

        _status.value = WsClientRuntimeStatus(
            serverUrl = url,
            state = WsClientConnectionState.RECONNECTING,
            lastError = errorMsg
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (activeWanted.get()) {
                openSocket(url)
            }
        }
    }

    private fun disconnectInternal(keepWanted: Boolean) {
        if (!keepWanted) activeWanted.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            currentSocket?.close(1000, "Client closing")
        } catch (_: Exception) {
        }
        currentSocket = null
    }

    companion object {
        const val DEFAULT_WS_CLIENT_URL = "ws://192.168.1.230:8080/ws"

        fun normalizeWsUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return DEFAULT_WS_CLIENT_URL
            val lower = trimmed.lowercase()
            return when {
                lower.startsWith("ws://") -> "ws://" + trimmed.substring(5)
                lower.startsWith("wss://") -> "wss://" + trimmed.substring(6)
                lower.startsWith("http://") -> "ws://" + trimmed.substring(7)
                lower.startsWith("https://") -> "wss://" + trimmed.substring(8)
                else -> "ws://$trimmed"
            }
        }
    }
}
