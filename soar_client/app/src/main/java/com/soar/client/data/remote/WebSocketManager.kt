package com.soar.client.data.remote

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    CONNECTED, CONNECTING, DISCONNECTED
}

/**
 * High-performance WebSocket manager for live SOAR threat streaming.
 * Handles auto-reconnect and delivers parsed events directly to Compose via SharedFlow.
 */
class WebSocketManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String = ""

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _events = MutableSharedFlow<PhishingEventDto>(extraBufferCapacity = 64)
    val events: SharedFlow<PhishingEventDto> = _events.asSharedFlow()

    fun connect(wsUrl: String) {
        currentUrl = wsUrl
        reconnectJob?.cancel()
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("WebSocketManager", "Connected to SOAR triage stream")
                _connectionStatus.value = ConnectionStatus.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = gson.fromJson(text, PhishingEventDto::class.java)
                    _events.tryEmit(event)
                } catch (e: Exception) {
                    Log.w("WebSocketManager", "Failed to parse triage event: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("WebSocketManager", "WebSocket failure: ${t.message}")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(5000)
            if (_connectionStatus.value == ConnectionStatus.DISCONNECTED && currentUrl.isNotEmpty()) {
                connect(currentUrl)
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}
