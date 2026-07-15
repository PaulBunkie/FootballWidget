package com.shrewd.bet

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
@Parcelize
data class FootballMatch(
    val away_team: String,
    val away_team_sofascore_id: Int?,
    val date: String,
    val favorite: String,
    val home_team: String,
    val home_team_sofascore_id: Int?,
    val k0: Double?,
    val k1: Double?,
    val k60: Double?,
    val sofascore_event_id: Long?,
    val time_utc: String
) : Parcelable {
    companion object {
        fun empty() = FootballMatch(
            away_team = "",
            away_team_sofascore_id = null,
            date = "",
            favorite = "",
            home_team = "",
            home_team_sofascore_id = null,
            k0 = null,
            k1 = null,
            k60 = null,
            sofascore_event_id = null,
            time_utc = ""
        )
    }
}

@JsonClass(generateAdapter = true)
data class MatchesResponse(
    val matches: List<FootballMatch>,
    val success: Boolean
)
