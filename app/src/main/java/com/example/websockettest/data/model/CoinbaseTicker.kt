package com.example.websockettest.data.model

data class CoinbaseTicker(
    val type: String,
    val sequence: Long,
    val product_id: String,
    val price: String?,
    val open_24h: String?,
    val high_24h: String?,
    val low_24h: String?,
    val volume_24h: String?,
    val volume_3d: String?,
    val best_bid: String?,
    val best_bid_size: String?,
    val best_ask: String?,
    val best_ask_size: String?,
    val side: String?,
    val time: String?,
    val trade_id: Long?,
    val last_size: String?
)
