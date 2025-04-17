package com.example.websockettest.network

import android.util.Log
import com.example.websockettest.data.model.CoinbaseTicker
import com.google.gson.Gson
import com.google.gson.JsonParser
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

private const val COINBASE_WS_URL = "wss://ws-feed.exchange.coinbase.com"
private const val RECONNECT_DELAY_MS = 5000L
private const val PING_INTERVAL_MS = 25000L

class CoinbaseWebSocketListener(private val gson: Gson) {

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentProductIds: List<String> = emptyList()
    private var isConnecting = false
    var manualDisconnect = false
    private var isReconnectingProgrammatically = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    private val _coinbaseTickerUpdates = MutableSharedFlow<Pair<String, String?>>(replay = 0)
    val coinbaseTickerUpdates: SharedFlow<Pair<String, String?>> = _coinbaseTickerUpdates.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<ConnectionStatus>(replay = 1)
    val connectionStatus: SharedFlow<ConnectionStatus> = _connectionStatus.asSharedFlow()

    sealed class ConnectionStatus {
        object Connected : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
        object Closed : ConnectionStatus()
        object Connecting : ConnectionStatus()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i("CoinbaseWS", "WebSocket Opened to Coinbase.")
            webSocket = ws
            isConnecting = false
            manualDisconnect = false
            isReconnectingProgrammatically = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
            subscribeToChannels(currentProductIds)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Log.v("CoinbaseWS_RAW", text)
            try {
                val jsonElement = JsonParser.parseString(text)
                if (!jsonElement.isJsonObject) {
                    Log.w("CoinbaseWS", "Received non-JSON message: $text")
                    return
                }
                val jsonObject = jsonElement.asJsonObject

                when (jsonObject.get("type")?.asString) {
                    "ticker" -> {
                        try {
                            val tickerData = gson.fromJson(jsonObject, CoinbaseTicker::class.java)
                            if (tickerData.product_id != null && tickerData.price != null) {
                                // Log.d("CoinbaseWS", "Ticker: ${tickerData.product_id} @ ${tickerData.price}")
                                scope.launch {
                                    _coinbaseTickerUpdates.emit(Pair(tickerData.product_id, tickerData.price))
                                }
                            } else {
                                // Log.v("CoinbaseWS", "Ticker received with null price for ${tickerData.product_id}")
                            }
                        } catch (e: JsonSyntaxException) {
                            Log.e("CoinbaseWS", "Ticker JSON Parsing Error: ${e.message}", e)
                        }
                    }
                    "subscriptions" -> {
                        Log.i("CoinbaseWS", "Subscription confirmation received: $text")
                    }
                    "error" -> {
                        Log.e("CoinbaseWS", "Error message received: $text")
                        val errorMsg = jsonObject.get("message")?.asString ?: "Unknown Coinbase Error"
                        scope.launch { _connectionStatus.emit(ConnectionStatus.Error(errorMsg)) }
                    }
                    else -> {
                        // Log.v("CoinbaseWS", "Received unhandled message type: $text")
                    }
                }

            } catch (e: Exception) {
                Log.e("CoinbaseWS", "Error processing message: ${e.message} \n Text: $text", e)
            }
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.w("CoinbaseWS", "WebSocket Closing: Code=$code, Reason='$reason'")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i("CoinbaseWS", "WebSocket Closed: Code=$code, Reason='$reason'")
            webSocket = null
            isConnecting = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Closed) }
            if (!manualDisconnect && !isReconnectingProgrammatically) {
                reconnect()
            }
            isReconnectingProgrammatically = false
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val errorMsg = "WebSocket Failure: ${t.message ?: "Unknown error"}"
            Log.e("CoinbaseWS", errorMsg, t)
            webSocket = null
            isConnecting = false
            isReconnectingProgrammatically = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Error(errorMsg)) }
            if (!manualDisconnect) {
                reconnect()
            }
        }
    }

    fun connect(productIds: List<String>) {
        if (webSocket != null && !isConnecting && productIds.toSet() == currentProductIds.toSet()) {
            Log.d("CoinbaseWS", "Already connected with the same Product IDs.")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
            return
        }
        if (isConnecting) {
            Log.w("CoinbaseWS", "Connection attempt already in progress.")
            return
        }

        isConnecting = true
        manualDisconnect = false
        currentProductIds = productIds.toList()

        if (currentProductIds.isEmpty()) {
            Log.w("CoinbaseWS", "No Product IDs provided to connect.")
            disconnect()
            isConnecting = false
            return
        }

        isReconnectingProgrammatically = true
        webSocket?.close(1000, "Reconnecting with new Product IDs")
        webSocket = null

        Log.i("CoinbaseWS", "Attempting to connect to $COINBASE_WS_URL")
        scope.launch { _connectionStatus.emit(ConnectionStatus.Connecting) }
        val request = Request.Builder().url(COINBASE_WS_URL).build()
        client.newWebSocket(request, listener)
    }

    private fun sendSubscriptionMessage(method: String, productIds: List<String>) {
        if (productIds.isEmpty()) return
        val currentWebSocket = webSocket
        if (currentWebSocket == null) {
            Log.w("CoinbaseWS", "Cannot $method, WebSocket is not connected.")
            return
        }

        val messageMap = mapOf(
            "type" to method,
            "product_ids" to productIds,
            "channels" to listOf("ticker")
        )
        val jsonMessage = gson.toJson(messageMap)
        Log.d("CoinbaseWS", "Sending $method message: $jsonMessage")
        val sent = currentWebSocket.send(jsonMessage)
        if (!sent) {
            Log.e("CoinbaseWS", "Failed to send $method message (queue full or closed)")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Error("Failed to send $method request"))}
            reconnect()
        }
    }

    private fun subscribeToChannels(productIds: List<String>) {
        sendSubscriptionMessage("subscribe", productIds)
    }

    fun updateSubscription(newProductIds: List<String>) {
        if (newProductIds.toSet() == currentProductIds.toSet() && webSocket != null && !isConnecting) {
            Log.d("CoinbaseWS", "Product IDs haven't changed and already connected.")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connected) }
            return
        }
        Log.i("CoinbaseWS", "Updating subscription to: $newProductIds")
        connect(newProductIds)
    }


    private fun reconnect() {
        if (isConnecting || manualDisconnect) return
        Log.i("CoinbaseWS", "Attempting Coinbase reconnect in ${RECONNECT_DELAY_MS / 1000} seconds...")
        isConnecting = true
        scope.launch {
            _connectionStatus.emit(ConnectionStatus.Error("Reconnecting..."))
            delay(RECONNECT_DELAY_MS)
            if (!manualDisconnect) {
                connect(currentProductIds)
            } else {
                isConnecting = false
            }
        }
    }

    fun disconnect() {
        Log.i("CoinbaseWS", "Manual disconnect requested.")
        manualDisconnect = true
        isConnecting = false
        isReconnectingProgrammatically = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        scope.launch { _connectionStatus.emit(ConnectionStatus.Closed) }
    }

    fun cleanup() {
        Log.d("CoinbaseWS", "Cleaning up WebSocket resources.")
        scope.cancel()
        disconnect()
    }
}