package com.shrewd.bet.network

import com.shrewd.bet.MatchesResponse
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

interface FootballApiService {
    @GET("api/football/favorites-today")
    suspend fun getFavoritesToday(): MatchesResponse

    @GET("api/v1/event/{id}")
    suspend fun getEventDetails(@Path("id") id: Long): SofaScoreEventResponse
}

@JsonClass(generateAdapter = true)
data class SofaScoreEventResponse(
    val event: SofaEvent
)

@JsonClass(generateAdapter = true)
data class SofaEvent(
    val status: SofaStatus,
    val homeScore: SofaScore,
    val awayScore: SofaScore,
    val currentPeriodStartTimestamp: Long? = null
)

@JsonClass(generateAdapter = true)
data class SofaStatus(
    val code: Int,
    val type: String
)

@JsonClass(generateAdapter = true)
data class SofaScore(
    val display: Int?,
    val penalties: Int? = null
)
