package com.example.websockettest.network

import retrofit2.Response
import retrofit2.http.GET

interface BinanceApiService {
    @GET("api/v3/exchangeInfo")
    suspend fun getExchangeInfo(): Response<ExchangeInfoResponse>

    companion object {
        const val BASE_URL = "https://api.binance.com/"
    }
}

data class ExchangeInfoResponse(
    val symbols: List<ApiSymbol>
)

data class ApiSymbol(
    val symbol: String,
    val status: String,
    val baseAsset: String,
    val quoteAsset: String
)