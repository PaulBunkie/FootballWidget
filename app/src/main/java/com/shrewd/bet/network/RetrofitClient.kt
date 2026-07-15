package com.shrewd.bet.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object RetrofitClient {
    private const val BASE_URL = "https://aitube.fly.dev/"
    private const val SOFASCORE_BASE_URL = "https://api.sofascore1.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val browserInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://www.sofascore.com/")
            .header("Cache-Control", "no-cache")
            .build()
        chain.proceed(request)
    }

    val rawClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    private val sofaClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(browserInterceptor)
        .build()

    val apiService: FootballApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(rawClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(FootballApiService::class.java)

    val sofaService: FootballApiService = Retrofit.Builder()
        .baseUrl(SOFASCORE_BASE_URL)
        .client(sofaClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(FootballApiService::class.java)
}
