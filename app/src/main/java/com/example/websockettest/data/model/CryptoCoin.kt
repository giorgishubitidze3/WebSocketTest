package com.example.websockettest.data.model

import android.util.Log
import com.example.websockettest.ui.SortCriteria
import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat

val PRESET_SYMBOLS_MAP = mapOf(
    "BTCUSDT" to "Bitcoin", "ETHUSDT" to "Ethereum", "SOLUSDT" to "Solana",
    "BNBUSDT" to "BNB", "XRPUSDT" to "XRP", "ADAUSDT" to "Cardano",
    "DOGEUSDT" to "Dogecoin", "AVAXUSDT" to "Avalanche", "DOTUSDT" to "Polkadot",
    "MATICUSDT" to "Polygon"
)
val PRESET_SYMBOLS = PRESET_SYMBOLS_MAP.keys.toList()

data class CryptoCoin(
    val symbol: String,
    val name: String,

    var binancePrice: BigDecimal = BigDecimal.ZERO,
    var binanceLastUpdatedAt: Long = 0L,
    var coinbasePrice: BigDecimal = BigDecimal.ZERO,
    var coinbaseLastUpdatedAt: Long = 0L,

    var spread: BigDecimal? = null,
    var isFavorite: Boolean = false
) {
    fun getBaseAsset(): String = symbol.removeSuffix("USDT")


    fun getCoinbaseProductId(): String {
        val base = getBaseAsset()
        return "$base-USD"
    }


    fun updateSpread() {
        if (binancePrice > BigDecimal.ZERO && coinbasePrice > BigDecimal.ZERO) {

            spread = (binancePrice - coinbasePrice).abs()
        } else {
            spread = null
        }
    }

    fun getFormattedSpreadPercentage(): String? {
        return if (spread != null && binancePrice > BigDecimal.ZERO) {
            try {
                if (binancePrice.compareTo(BigDecimal.ZERO) == 0) return null

                val percentage = (spread!! / binancePrice) * BigDecimal(100)
                val nf = NumberFormat.getPercentInstance()
                nf.maximumFractionDigits = 2
                nf.minimumFractionDigits = 2
                nf.format(percentage.divide(BigDecimal(100), MathContext.DECIMAL64))
            } catch (e: ArithmeticException) {
                Log.e("SpreadFormat", "Error calculating spread percentage for $symbol", e)
                null
            }
        } else {
            null
        }
    }
}


data class TickerUpdate(
    val s: String,
    val c: String
)

data class CryptoScreenState(

    val favoriteCoins: List<CryptoCoin> = emptyList(),
    val isFavoritesLoading: Boolean = true,
    val favoritesError: String? = null,
    val availablePresetFavorites: List<String> = emptyList(),
    val sortBy: SortCriteria = SortCriteria.NAME,
    val isSortAscending: Boolean = true,

    val allCoins: List<CryptoCoin> = emptyList(),
    val isAllCoinsLoading: Boolean = true,
    val allCoinsError: String? = null,
    val searchQuery: String = "",

    val selectedTabIndex: Int = 0)

