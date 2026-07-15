package com.shrewd.bet.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.shrewd.bet.FootballMatch
import com.shrewd.bet.MainActivity
import com.shrewd.bet.MatchesResponse
import com.shrewd.bet.network.RetrofitClient
import com.shrewd.bet.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.shrewd.bet.model.LiveScoreCache
import com.shrewd.bet.util.LogoManager

class MatchRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun fetchMatches(): Result<MatchesResponse> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getFavoritesToday()
            if (response.success) {
                // Download logos for all teams PARALLEL
                val client = RetrofitClient.rawClient
                coroutineScope {
                    response.matches.flatMap { match ->
                        listOf(
                            async { LogoManager.downloadLogo(context, client, match.home_team_sofascore_id) },
                            async { LogoManager.downloadLogo(context, client, match.away_team_sofascore_id) }
                        )
                    }.awaitAll()
                }

                // Cache successful response
                cacheMatches(response)
                Result.success(response)
            } else {
                Result.failure(Exception("API returned unsuccessful response"))
            }
        } catch (e: Exception) {
            // On network error, try to load cached data
            val cached = loadCachedMatches()
            if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    private fun cacheMatches(response: MatchesResponse) {
        val oldJson = prefs.getString(KEY_CACHED_MATCHES, null)
        val finalMatches = if (oldJson != null) {
            try {
                val oldData = JsonUtil.fromJson(oldJson)
                val newMatches = response.matches.toMutableList()
                val newIds = newMatches.mapNotNull { it.sofascore_event_id }.toSet()
                
                // Ищем в старом списке те, что еще в лайве, но пропали из нового API
                val now = System.currentTimeMillis()
                val liveToKeep = oldData?.matches?.filter { oldMatch ->
                    val id = oldMatch.sofascore_event_id?.toString() ?: ""
                    if (id.isEmpty() || newIds.contains(oldMatch.sofascore_event_id)) return@filter false
                    
                    val ld = LiveScoreCache.get(context, id)
                    val st = getMatchStartTimeMillis(oldMatch.time_utc, oldMatch.date)
                    
                    val isFinished = ld?.isFinished == true
                    val lastPush = ld?.pushTimestampMs ?: 0L
                    val hasRecentPush = lastPush > 0 && (now - lastPush) < (5L * 60_000L)
                    val isExpired = st > 0 && now > (st + (180L * 60_000L))
                    
                    // Оставляем только если матч не закончен и (еще не просрочен ИЛИ были пуши недавно)
                    !isFinished && (!isExpired || hasRecentPush)
                } ?: emptyList()
                
                if (liveToKeep.isNotEmpty()) {
                    Log.d("MatchRepository", "Merging ${liveToKeep.size} live matches from old cache")
                    newMatches.addAll(liveToKeep)
                    newMatches.sortBy { getMatchStartTimeMillis(it.time_utc, it.date) }
                }
                response.copy(matches = newMatches)
            } catch (e: Exception) {
                response
            }
        } else {
            response
        }

        prefs.edit()
            .putString(KEY_CACHED_MATCHES, JsonUtil.toJson(finalMatches))
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    private fun getMatchStartTimeMillis(timeUtc: String, date: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val parsed = sdf.parse("$date $timeUtc") ?: return 0L
            parsed.time
        } catch (_: Exception) {
            0L
        }
    }

    fun loadCachedMatches(): MatchesResponse? {
        val json = prefs.getString(KEY_CACHED_MATCHES, null) ?: return null
        return try {
            JsonUtil.fromJson(json)
        } catch (e: Exception) {
            Log.e("MatchRepository", "Error parsing cached matches", e)
            null
        }
    }

    fun loadFilteredMatches(): List<FootballMatch> {
        val data = loadCachedMatches() ?: return emptyList()
        val threshold = MainActivity.getFavoriteOddsThreshold(context).toDouble()
        
        return data.matches.filter { match ->
            val k0 = match.k0
            // Если k0 нет, считаем что фильтр проходит (или наоборот? 
            // Обычно фавориты всегда имеют k0. Если нет k0, может это не топ матч.
            // Оставим как есть, если k0 null - не фильтруем)
            k0 == null || k0 <= threshold
        }
    }

    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0)
    }

    fun getCurrentMatchId(appWidgetId: Int): String? {
        return prefs.getString(KEY_WIDGET_MATCH_ID_PREFIX + appWidgetId, null)
    }

    fun setCurrentMatchId(appWidgetId: Int, matchId: String?) {
        prefs.edit().putString(KEY_WIDGET_MATCH_ID_PREFIX + appWidgetId, matchId).apply()
    }


    companion object {
        private const val PREFS_NAME = "football_widget_prefs"
        private const val KEY_CACHED_MATCHES = "cached_matches"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_WIDGET_MATCH_ID_PREFIX = "widget_match_id_"
    }
}
