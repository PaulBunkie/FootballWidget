package com.shrewd.bet.util

import com.shrewd.bet.MatchesResponse
import com.shrewd.bet.model.LiveMatchData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object JsonUtil {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(MatchesResponse::class.java)
    private val liveAdapter = moshi.adapter(LiveMatchData::class.java)

    fun toJson(response: MatchesResponse): String {
        return adapter.toJson(response)
    }

    fun fromJson(json: String): MatchesResponse? {
        return adapter.fromJson(json)
    }

    fun toJsonLive(data: LiveMatchData): String {
        return liveAdapter.toJson(data)
    }

    fun fromJsonLive(json: String): LiveMatchData? {
        return liveAdapter.fromJson(json)
    }
}
