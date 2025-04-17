package com.example.websockettest.network

import android.util.Log
import com.example.websockettest.data.model.TickerUpdate
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val BINANCE_WS_BASE_URL = "wss://stream.binance.com:9443/stream?streams="
private const val RECONNECT_DELAY_MS = 5000L
private const val PING_INTERVAL_MS = 30000L

class BinanceWebSocketListener(private val gson: Gson) {

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var currentSymbols: List<String> = emptyList()
    private var isConnecting = false
    var manualDisconnect = false
    private var isReconnectingProgrammatically = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tickerUpdates = MutableSharedFlow<TickerUpdate>(replay = 0)
    val tickerUpdates: SharedFlow<TickerUpdate> = _tickerUpdates.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<ConnectionStatus>(replay = 1)
    val connectionStatus: SharedFlow<ConnectionStatus> = _connectionStatus.asSharedFlow()

    sealed class ConnectionStatus {
        object Connected : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
        object Closed : ConnectionStatus()
    }


    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("BinanceWebSocket", "WebSocket Opened")
            isConnecting = false
            manualDisconnect = false
            isReconnectingProgrammatically = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Log.d("BinanceWebSocket", "Received: $text")
            try {
                val wrapper = gson.fromJson(text, Map::class.java)
                val data = wrapper["data"]
                if (data != null) {
                    val dataJson = gson.toJson(data)
                    val tickerUpdate = gson.fromJson(dataJson, TickerUpdate::class.java)
                    scope.launch { _tickerUpdates.emit(tickerUpdate) }
                } else {
                    Log.w("BinanceWebSocket", "Received message without 'data' field: $text")
                }

            } catch (e: JsonSyntaxException) {
                Log.e("BinanceWebSocket", "JSON Parsing Error: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("BinanceWebSocket", "Error processing message: ${e.message}", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w("BinanceWebSocket", "WebSocket Closing: Code=$code, Reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i("BinanceWebSocket", "WebSocket Closed: Code=$code, Reason=$reason")
            isConnecting = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Closed) }

            if (!manualDisconnect && !isReconnectingProgrammatically) {
                reconnect()
            }
            isReconnectingProgrammatically = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorMsg = "WebSocket Failure: ${t.message ?: "Unknown error"}"
            Log.e("BinanceWebSocket", errorMsg, t)
            isConnecting = false
            isReconnectingProgrammatically = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Error(errorMsg)) }
            if (!manualDisconnect) {
                reconnect()
            }
        }
    }

    fun connect(symbols: List<String>) {
        if (webSocket != null && !isConnecting && symbols.toSet() == currentSymbols.toSet()) {
            Log.d("BinanceWebSocket", "Already connected with the same symbols.")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
            return
        }
        if (isConnecting) {
            Log.w("BinanceWebSocket", "Connection attempt already in progress.")
            return
        }

        isConnecting = true
        manualDisconnect = false
        currentSymbols = symbols.toList()

        if (currentSymbols.isEmpty()) {
            Log.w("BinanceWebSocket", "No symbols provided to connect.")
            disconnect()
            isConnecting = false
            return
        }

        isReconnectingProgrammatically = true
        Log.d("BinanceWebSocket", "Setting isReconnectingProgrammatically=true before closing old socket.")
        webSocket?.close(1000, "Reconnecting with new symbols")
        webSocket = null

        val streamNames = currentSymbols.joinToString("/") { "${it.lowercase()}@miniTicker" }
        val requestUrl = "$BINANCE_WS_BASE_URL$streamNames"

        Log.i("BinanceWebSocket", "Connecting to: $requestUrl")
        scope.launch { _connectionStatus.emit(ConnectionStatus.Error("Connecting...")) }

        val request = Request.Builder().url(requestUrl).build()
        client.newWebSocket(request, webSocketListener)
    }

    private fun reconnect() {
        if (isConnecting || manualDisconnect) return

        Log.i("BinanceWebSocket", "Attempting to reconnect in ${RECONNECT_DELAY_MS / 1000} seconds...")
        isConnecting = true
        scope.launch {
            _connectionStatus.emit(ConnectionStatus.Error("Reconnecting..."))
            delay(RECONNECT_DELAY_MS)
            if (!manualDisconnect) {
                Log.d("BinanceWebSocket", "Reconnect timer finished, calling connect.")
                connect(currentSymbols)
            } else {
                Log.d("BinanceWebSocket", "Reconnect timer finished, but manualDisconnect=true.")
                isConnecting = false
            }
        }
    }

    fun disconnect() {
        Log.i("BinanceWebSocket", "Manual disconnect requested.")
        manualDisconnect = true
        isConnecting = false
        isReconnectingProgrammatically = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        scope.launch { _connectionStatus.emit(ConnectionStatus.Closed) }
    }

    fun updateSubscription(newSymbols: List<String>) {
        if (newSymbols.toSet() == currentSymbols.toSet() && webSocket != null && !isConnecting) {
            Log.d("BinanceWebSocket", "Symbols haven't changed and already connected.")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
            return
        }
        Log.i("BinanceWebSocket", "Updating subscription to: $newSymbols")
        isReconnectingProgrammatically = true
        connect(newSymbols)
    }

    fun cleanup() {
        Log.d("BinanceWebSocket", "Cleaning up WebSocket resources.")
        scope.cancel()
        disconnect()
    }
}