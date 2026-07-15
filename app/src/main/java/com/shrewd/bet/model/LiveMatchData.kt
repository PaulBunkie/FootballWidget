package com.shrewd.bet.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class LiveMatchData(
    val match_id: String,
    val score_home: String,
    val score_away: String,
    val status: String,
    val minute: String,
    val pushTimestampMs: Long = 0L
) {
    /** 🟢 фаворит выигрывает, 🟡 ничья, 🔴 проигрывает, null — нет счёта */
    fun getFavoriteStatus(favoriteTeam: String, homeTeam: String): String? {
        val home = score_home.toIntOrNull() ?: return null
        val away = score_away.toIntOrNull() ?: return null

        val isHomeFavorite = homeTeam == favoriteTeam
        val favScore = if (isHomeFavorite) home else away
        val oppScore = if (isHomeFavorite) away else home

        return when {
            favScore > oppScore -> STATUS_WINNING
            favScore == oppScore -> STATUS_DRAW
            else -> STATUS_LOSING
        }
    }

    val isLive: Boolean get() = status == "live"
    val isFinished: Boolean get() = status == "finished"

    val scoreText: String get() = "$score_home : $score_away"

    companion object {
        const val STATUS_WINNING = "winning"    // 🟢
        const val STATUS_DRAW = "draw"          // 🟡
        const val STATUS_LOSING = "losing"      // 🔴
    }
}