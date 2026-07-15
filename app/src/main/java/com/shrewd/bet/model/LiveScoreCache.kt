package com.shrewd.bet.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Хранилище live-счёта для матчей.
 * Ключ — sofascore_event_id (он же match_id из FCM-пуша).
 */
object LiveScoreCache {
    private const val TAG = "LiveScoreCache"
    private const val PREFS_NAME = "live_score_cache"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, data: LiveMatchData) {
        Log.d(TAG, "Saving live data for match ${data.match_id}: ${data.scoreText} (${data.status})")
        val json = com.shrewd.bet.util.JsonUtil.toJsonLive(data)
        prefs(context).edit {
            putString("data:${data.match_id}", json)
            // Сохраняем push_ts отдельно для быстрой очистки, если нужно
            putLong("push_ts:${data.match_id}", data.pushTimestampMs)
        }
    }

    fun get(context: Context, matchId: String): LiveMatchData? {
        val p = prefs(context)
        val json = p.getString("data:$matchId", null) ?: return null
        return try {
            com.shrewd.bet.util.JsonUtil.fromJsonLive(json)
        } catch (e: Exception) {
            null
        }
    }
}