package com.example.websockettest.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor

object NetworkModule {

    val gson: Gson by lazy { GsonBuilder().create() }

    private fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BinanceApiService.BASE_URL)
            .client(provideOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val binanceApiService: BinanceApiService by lazy {
        retrofit.create(BinanceApiService::class.java)
    }

    val binanceWebSocketListener: BinanceWebSocketListener by lazy {
        BinanceWebSocketListener(gson)
    }

    val coinbaseWebSocketListener: CoinbaseWebSocketListener by lazy {
        CoinbaseWebSocketListener(gson)
    }
}