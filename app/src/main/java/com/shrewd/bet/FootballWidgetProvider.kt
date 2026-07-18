package com.shrewd.bet

import android.util.Log
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.work.*
import com.shrewd.bet.network.RetrofitClient
import com.shrewd.bet.repository.MatchRepository
import com.shrewd.bet.util.JsonUtil
import com.shrewd.bet.util.LogoManager
import com.google.firebase.messaging.FirebaseMessaging
import com.shrewd.bet.model.LiveMatchData
import com.shrewd.bet.model.LiveScoreCache
import com.shrewd.bet.worker.MatchUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class FootballWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val repository = MatchRepository(context)
        val cachedMatches = repository.loadFilteredMatches()

        appWidgetIds.forEach { id ->
            if (cachedMatches.isEmpty()) {
                // Если кэш пуст, показываем статус загрузки
                showLoadingStatus(context, appWidgetManager, id)
            } else {
                // Если есть данные в кэше, сразу отрисовываем их
                updateWidgetUi(context, appWidgetManager, id)
            }
        }

        // Запускаем фоновое обновление
        widgetScope.launch(Dispatchers.IO) {
            val result = repository.fetchMatches()
            if (result.isSuccess) {
                launch(Dispatchers.Main) {
                    cleanupStaleMatches(context)
                    appWidgetIds.forEach { appWidgetId ->
                        updateWidgetUi(context, appWidgetManager, appWidgetId)
                    }
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidgetUi(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            cleanupStaleMatches(context)
            val repository = MatchRepository(context)
            val matches = repository.loadFilteredMatches()

            if (matches.isNotEmpty()) {
                val currentId = repository.getCurrentMatchId(appWidgetId)
                var currentIndex = matches.indexOfFirst { it.sofascore_event_id?.toString() == currentId }.coerceAtLeast(0)
                
                when (action) {
                    ACTION_PREV -> {
                        currentIndex = (currentIndex - 1 + matches.size) % matches.size
                    }
                    ACTION_NEXT -> {
                        currentIndex = (currentIndex + 1) % matches.size
                    }
                }
                repository.setCurrentMatchId(appWidgetId, matches[currentIndex].sofascore_event_id?.toString())
            }
            updateWidgetUi(context, AppWidgetManager.getInstance(context), appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        scheduleMatchUpdate(context)
        
        // Сразу запрашиваем свежий список матчей при включении виджета
        widgetScope.launch(Dispatchers.IO) {
            MatchRepository(context).fetchMatches()
            launch(Dispatchers.Main) {
                updateAllWidgets(context)
            }
        }

        FirebaseMessaging.getInstance().subscribeToTopic("matches")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Subscribed to FCM topic: matches")
                } else {
                    Log.e(TAG, "Failed to subscribe to FCM topic", task.exception)
                }
            }
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MATCH_UPDATE_WORK)
    }

    private fun scheduleMatchUpdate(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val updateRequest = PeriodicWorkRequestBuilder<MatchUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                MATCH_UPDATE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
    }

    companion object {
        private const val TAG = "FootballWidget"
        private const val MATCH_UPDATE_WORK = "match_update_work"
        private const val ACTION_PREV = "com.shrewd.bet.ACTION_PREV"
        private const val ACTION_NEXT = "com.shrewd.bet.ACTION_NEXT"

        private val widgetScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var lastSofaRequestTime = 0L

        fun updateAllWidgets(context: Context, focusMatchId: String? = null) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, FootballWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            val repository = MatchRepository(context)

            if (focusMatchId != null) {
                appWidgetIds.forEach { id ->
                    repository.setCurrentMatchId(id, focusMatchId)
                }
            }

            appWidgetIds.forEach { id ->
                updateWidgetUi(context, appWidgetManager, id)
            }
        }

        private fun updateWidgetUi(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            cleanupStaleMatches(context)
            val repository = MatchRepository(context)
            val matches = repository.loadFilteredMatches()
            val now = System.currentTimeMillis()
            
            Log.d(TAG, "Updating UI for widget $appWidgetId. Matches count: ${matches.size}")

            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val layoutId = if (minHeight >= 100) R.layout.widget_football_match_large else R.layout.widget_football_match
            
            val views = RemoteViews(context.packageName, layoutId)
            val transparency = MainActivity.getWidgetTransparency(context)
            val alphaInt = (transparency * 255 / 100).coerceIn(0, 255)
            val alphaHex = String.format("%02X", alphaInt)
            
            views.setImageViewResource(R.id.widgetBackground, R.drawable.widget_rounded_bg)

            if (matches.isNotEmpty()) {
                val currentId = repository.getCurrentMatchId(appWidgetId)
                var index = matches.indexOfFirst { it.sofascore_event_id?.toString() == currentId }
                
                if (index == -1) {
                    index = 0
                }

                val match = matches[index]
                
                // Reset visibility
                views.setViewVisibility(R.id.homeTeam, View.VISIBLE)
                views.setViewVisibility(R.id.awayTeam, View.VISIBLE)
                views.setViewVisibility(R.id.homeLogo, View.VISIBLE)
                views.setViewVisibility(R.id.awayLogo, View.VISIBLE)
                views.setViewVisibility(R.id.btnPrev, View.VISIBLE)
                views.setViewVisibility(R.id.btnNext, View.VISIBLE)
                views.setViewVisibility(R.id.btnDetails, View.VISIBLE)
                views.setViewVisibility(R.id.oddsContainer, View.VISIBLE)
                
                views.setTextViewText(R.id.homeTeam, match.home_team)
                views.setTextViewText(R.id.awayTeam, match.away_team)
                views.setViewVisibility(R.id.homeStar, if (match.favorite == match.home_team) View.VISIBLE else View.INVISIBLE)
                views.setViewVisibility(R.id.awayStar, if (match.favorite == match.away_team) View.VISIBLE else View.INVISIBLE)

                val liveData = match.sofascore_event_id?.toString()?.let { id ->
                    LiveScoreCache.get(context, id)
                }

                // Autonomous SofaScore Sync Trigger for ALL matches
                matches.forEach { m ->
                    val mId = m.sofascore_event_id ?: return@forEach
                    val mLd = LiveScoreCache.get(context, mId.toString())
                    val mSt = getMatchStartTimeMillis(m.time_utc, m.date)
                    
                    val isLiveStatus = mLd?.isLive == true || (mLd == null && now >= mSt && mSt > 0)
                    val isWithin3Hours = mSt > 0 && now < (mSt + (180L * 60_000L))
                    
                    if (isLiveStatus && isWithin3Hours) {
                        val lastPush = mLd?.pushTimestampMs ?: 0L
                        val timeSincePush = now - lastPush
                        val timeSinceLastRequest = now - lastSofaRequestTime

                        if (timeSincePush > 15 * 60_000L && timeSinceLastRequest > 30_000L) {
                            lastSofaRequestTime = now
                            fetchSofaScoreUpdate(context, mId)
                        }
                    }
                }

                val isLive = liveData?.isLive == true || (liveData == null && isMatchStarted(match, now))
                
                if (isLive) {
                    views.setViewVisibility(R.id.matchDate, View.GONE)
                    views.setViewVisibility(R.id.matchTimeNormal, View.GONE)

                    val scoreText = liveData?.scoreText ?: "0 : 0"
                    val liveMinute = if (liveData != null && liveData.minute.isNotEmpty()) {
                        formatLiveMinute(liveData, now)
                    } else {
                        computeFallbackMinute(match, now).toString() + "'"
                    }
                    
                    views.setTextViewText(R.id.matchScore, scoreText)
                    views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                    
                    if (layoutId == R.layout.widget_football_match_large) {
                        views.setViewVisibility(R.id.matchTime, View.GONE)
                        views.setTextViewText(R.id.matchTimeNormal, liveMinute)
                        views.setViewVisibility(R.id.matchTimeNormal, View.VISIBLE)
                    } else {
                        views.setTextViewText(R.id.matchTime, liveMinute)
                        views.setViewVisibility(R.id.matchTime, View.VISIBLE)
                    }
                    
                    val statusColor = if (liveData != null) getLiveBackgroundColor(match, liveData, "FF") else "#FFF57F17"
                    views.setInt(R.id.widgetBackground, "setColorFilter", statusColor.toColorInt())
                    views.setInt(R.id.widgetBackground, "setImageAlpha", alphaInt)
                } else {
                    val st = getMatchStartTimeMillis(match.time_utc, match.date)
                    val localTime = utcToLocalTime(match.time_utc, match.date)

                    if (isMatchTodayLocal(st)) {
                        if (layoutId == R.layout.widget_football_match_large) {
                            views.setTextViewText(R.id.matchScore, localTime)
                            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                            views.setViewVisibility(R.id.matchDate, View.GONE)
                            
                            views.setViewVisibility(R.id.matchTime, View.GONE)
                            views.setTextViewText(R.id.matchTimeNormal, context.getString(R.string.today))
                            views.setViewVisibility(R.id.matchTimeNormal, View.VISIBLE)
                        } else {
                            views.setTextViewText(R.id.matchScore, localTime)
                            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                            views.setViewVisibility(R.id.matchTime, View.GONE)
                        }
                    } else if (isMatchTomorrowLocal(st)) {
                        if (layoutId == R.layout.widget_football_match_large) {
                            views.setTextViewText(R.id.matchScore, localTime)
                            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                            views.setViewVisibility(R.id.matchDate, View.GONE)
                            
                            views.setViewVisibility(R.id.matchTime, View.GONE)
                            views.setTextViewText(R.id.matchTimeNormal, context.getString(R.string.tomorrow))
                            views.setViewVisibility(R.id.matchTimeNormal, View.VISIBLE)
                        } else {
                            views.setTextViewText(R.id.matchScore, context.getString(R.string.tomorrow))
                            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                            views.setTextViewText(R.id.matchTime, localTime)
                            views.setViewVisibility(R.id.matchTime, View.VISIBLE)
                        }
                    } else {
                        val formattedDate = formatDateOnly(st)
                        if (layoutId == R.layout.widget_football_match_large) {
                            views.setViewVisibility(R.id.matchScore, View.GONE)
                            views.setTextViewText(R.id.matchDate, formattedDate)
                            views.setViewVisibility(R.id.matchDate, View.VISIBLE)
                            
                            views.setViewVisibility(R.id.matchTime, View.GONE)
                            views.setTextViewText(R.id.matchTimeNormal, localTime)
                            views.setViewVisibility(R.id.matchTimeNormal, View.VISIBLE)
                        } else {
                            views.setTextViewText(R.id.matchScore, formattedDate)
                            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
                            views.setTextViewText(R.id.matchTime, localTime)
                            views.setViewVisibility(R.id.matchTime, View.VISIBLE)
                        }
                    }
                    views.setInt(R.id.widgetBackground, "setColorFilter", "#FF424242".toColorInt())
                    views.setInt(R.id.widgetBackground, "setImageAlpha", alphaInt)
                }

                // Odds logic: Show always
                views.setViewVisibility(R.id.oddsContainer, View.VISIBLE)
                
                val favStatus = liveData?.getFavoriteStatus(match.favorite, match.home_team)
                val isYellowBg = (liveData?.isLive == true && favStatus == LiveMatchData.STATUS_DRAW) || 
                                (liveData == null && isMatchStarted(match, now))
                
                if (isYellowBg) {
                    views.setTextColor(R.id.oddsHome, "#1B5E20".toColorInt())
                    views.setTextColor(R.id.oddsAway, "#B71C1C".toColorInt())
                    views.setTextColor(R.id.oddsDraw, "#1E1E1E".toColorInt())
                    views.setTextColor(R.id.arrow1, "#661E1E1E".toColorInt())
                    views.setTextColor(R.id.arrow2, "#661E1E1E".toColorInt())
                } else {
                    views.setTextColor(R.id.oddsHome, "#4CAF50".toColorInt())
                    views.setTextColor(R.id.oddsAway, "#F44336".toColorInt())
                    views.setTextColor(R.id.oddsDraw, "#FFFFFF".toColorInt())
                    views.setTextColor(R.id.arrow1, "#80FFFFFF".toColorInt())
                    views.setTextColor(R.id.arrow2, "#80FFFFFF".toColorInt())
                }
                
                // Score and time always white/yellow
                views.setTextColor(R.id.matchScore, "#FFFFFF".toColorInt())
                views.setTextColor(R.id.matchTime, "#FDD835".toColorInt())
                views.setTextColor(R.id.matchTimeNormal, "#FDD835".toColorInt())

                setOddsText(views, R.id.oddsHome, match.k0)
                views.setViewVisibility(R.id.arrow1, View.VISIBLE)
                setOddsText(views, R.id.oddsAway, match.k1)
                
                if (match.k60 != null) {
                    views.setViewVisibility(R.id.arrow2, View.VISIBLE)
                    setOddsText(views, R.id.oddsDraw, match.k60)
                } else {
                    views.setViewVisibility(R.id.arrow2, View.GONE)
                    views.setViewVisibility(R.id.oddsDraw, View.GONE)
                }

                views.removeAllViews(R.id.dotsContainer)
                val dotsCount = Math.min(matches.size, 5)
                if (dotsCount > 1) {
                    views.setViewVisibility(R.id.dotsContainer, View.VISIBLE)
                    for (i in 0 until dotsCount) {
                        val dot = RemoteViews(context.packageName, R.layout.widget_dot_item)
                        dot.setImageViewResource(R.id.dotImage, if (i == index) R.drawable.ic_dot_active else R.drawable.ic_dot_inactive)
                        views.addView(R.id.dotsContainer, dot)
                    }
                } else {
                    views.setViewVisibility(R.id.dotsContainer, View.GONE)
                }

                LogoManager.getLogo(context, match.home_team_sofascore_id)?.let {
                    views.setImageViewBitmap(R.id.homeLogo, it)
                } ?: views.setImageViewResource(R.id.homeLogo, R.drawable.ic_football)
                LogoManager.getLogo(context, match.away_team_sofascore_id)?.let {
                    views.setImageViewBitmap(R.id.awayLogo, it)
                } ?: views.setImageViewResource(R.id.awayLogo, R.drawable.ic_football)

                val prevIntent = getPendingSelfIntent(context, ACTION_PREV, appWidgetId)
                val nextIntent = getPendingSelfIntent(context, ACTION_NEXT, appWidgetId)
                views.setOnClickPendingIntent(R.id.btnPrev, prevIntent)
                views.setOnClickPendingIntent(R.id.btnNext, nextIntent)
                views.setOnClickPendingIntent(R.id.homeLogo, prevIntent)
                views.setOnClickPendingIntent(R.id.awayLogo, nextIntent)
                
                match.sofascore_event_id?.let { eventId ->
                    val intent = Intent(context, MomentumActivity::class.java).apply {
                        putExtra(MomentumActivity.EXTRA_EVENT_ID, eventId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    views.setOnClickPendingIntent(R.id.btnDetails, PendingIntent.getActivity(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                
            } else {
                showNoMatches(context, views, appWidgetManager, appWidgetId)
                return
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun cleanupStaleMatches(context: Context) {
            val repository = MatchRepository(context)
            val data = repository.loadCachedMatches() ?: return
            val now = System.currentTimeMillis()
            
            val activeMatches = data.matches.filter { match ->
                val id = match.sofascore_event_id?.toString() ?: ""
                val ld = LiveScoreCache.get(context, id)
                val st = getMatchStartTimeMillis(match.time_utc, match.date)

                val isFinished = ld?.isFinished == true
                val lastPush = ld?.pushTimestampMs ?: 0L
                val hasRecentPush = lastPush > 0 && (now - lastPush) < (5L * 60_000L)
                val isExpired = st > 0 && now > (st + (180L * 60_000L))
                
                val shouldDelete = isFinished || (isExpired && !hasRecentPush)
                
                if (shouldDelete) {
                    Log.d("MatchCleanup", "LOCAL PURGE: $id (${match.home_team}) | fin=$isFinished, exp=$isExpired")
                    context.getSharedPreferences("live_score_cache", Context.MODE_PRIVATE).edit {
                        remove("score_home:$id")
                        remove("score_away:$id")
                        remove("status:$id")
                        remove("minute:$id")
                        remove("push_ts:$id")
                    }
                }
                !shouldDelete
            }
            
            if (activeMatches.size != data.matches.size) {
                val prefs = context.getSharedPreferences("football_widget_prefs", Context.MODE_PRIVATE)
                prefs.edit {
                    putString("cached_matches", JsonUtil.toJson(data.copy(matches = activeMatches)))
                }
                
                Log.d("MatchCleanup", "Local cache updated. Remaining: ${activeMatches.size}")
            }
        }

        private fun showLoadingStatus(context: Context, manager: AppWidgetManager, id: Int) {
            val transparency = MainActivity.getWidgetTransparency(context)
            val alphaInt = (transparency * 255 / 100).coerceIn(0, 255)
            
            val options = manager.getAppWidgetOptions(id)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val layoutId = if (minHeight >= 100) R.layout.widget_football_match_large else R.layout.widget_football_match
            val views = RemoteViews(context.packageName, layoutId)

            views.setImageViewResource(R.id.widgetBackground, R.drawable.widget_rounded_bg)
            views.setTextViewText(R.id.matchScore, context.getString(R.string.loading_matches))
            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
            
            // Скрываем всё лишнее, чтобы текст был по центру
            views.setViewVisibility(R.id.homeLogo, View.GONE)
            views.setViewVisibility(R.id.awayLogo, View.GONE)
            views.setViewVisibility(R.id.btnPrev, View.GONE)
            views.setViewVisibility(R.id.btnNext, View.GONE)
            views.setViewVisibility(R.id.matchTime, View.GONE)
            views.setViewVisibility(R.id.matchDate, View.GONE)
            views.setViewVisibility(R.id.matchTimeNormal, View.GONE)
            views.setViewVisibility(R.id.homeTeam, View.GONE)
            views.setViewVisibility(R.id.awayTeam, View.GONE)
            views.setViewVisibility(R.id.homeStar, View.GONE)
            views.setViewVisibility(R.id.awayStar, View.GONE)
            views.setViewVisibility(R.id.oddsContainer, View.GONE)
            views.setViewVisibility(R.id.dotsContainer, View.GONE)
            
            views.setInt(R.id.widgetBackground, "setColorFilter", "#FF424242".toColorInt())
            views.setInt(R.id.widgetBackground, "setImageAlpha", alphaInt)
            views.setOnClickPendingIntent(R.id.btnDetails, null)
            manager.updateAppWidget(id, views)
        }

        private fun showNoMatches(context: Context, views: RemoteViews, manager: AppWidgetManager, id: Int) {
            val transparency = MainActivity.getWidgetTransparency(context)
            val alphaInt = (transparency * 255 / 100).coerceIn(0, 255)
            
            views.setImageViewResource(R.id.widgetBackground, R.drawable.widget_rounded_bg)
            views.setTextViewText(R.id.matchScore, context.getString(R.string.no_active_matches))
            views.setViewVisibility(R.id.matchScore, View.VISIBLE)
            
            // Центрируем текст программно для маленького виджета, так как там горизонтальный Layout
            // Мы просто скроем всё остальное, а matchScore сделаем на всю ширину
            views.setViewVisibility(R.id.homeLogo, View.GONE)
            views.setViewVisibility(R.id.awayLogo, View.GONE)
            views.setViewVisibility(R.id.btnPrev, View.GONE)
            views.setViewVisibility(R.id.btnNext, View.GONE)
            
            views.setViewVisibility(R.id.matchTime, View.GONE)
            views.setViewVisibility(R.id.matchDate, View.GONE)
            views.setViewVisibility(R.id.matchTimeNormal, View.GONE)
            views.setViewVisibility(R.id.homeTeam, View.GONE)
            views.setViewVisibility(R.id.awayTeam, View.GONE)
            views.setViewVisibility(R.id.homeStar, View.GONE)
            views.setViewVisibility(R.id.awayStar, View.GONE)
            views.setViewVisibility(R.id.oddsContainer, View.GONE)
            views.setViewVisibility(R.id.dotsContainer, View.GONE)

            views.setInt(R.id.widgetBackground, "setColorFilter", "#FF424242".toColorInt())
            views.setInt(R.id.widgetBackground, "setImageAlpha", alphaInt)
            views.setOnClickPendingIntent(R.id.btnDetails, null)
            manager.updateAppWidget(id, views)
        }

        private fun isMatchStarted(match: FootballMatch, now: Long): Boolean {
            val startMs = getMatchStartTimeMillis(match.time_utc, match.date)
            return startMs > 0 && now >= startMs
        }

        private fun fetchSofaScoreUpdate(context: Context, eventId: Long) {
            widgetScope.launch(Dispatchers.IO) {
                try {
                    val response = RetrofitClient.sofaService.getEventDetails(eventId)
                    val event = response.event
                    val status = event.status
                    val now = System.currentTimeMillis()
                    
                    val minuteStr = when (status.code) {
                        6 -> { // 1st half
                            val start = event.currentPeriodStartTimestamp?.let { it * 1000L } ?: now
                            val m = ((now - start) / 60_000L).toInt().coerceAtLeast(0)
                            if (m > 45) "45+${m-45}" else m.toString()
                        }
                        7 -> "45" // Halftime
                        8 -> { // 2nd half
                            val start = event.currentPeriodStartTimestamp?.let { it * 1000L } ?: now
                            val m = (45 + (now - start) / 60_000L).toInt().coerceAtLeast(46)
                            if (m > 90) "90+${m-90}" else m.toString()
                        }
                        50 -> "PEN"
                        else -> ""
                    }

                    val liveData = LiveMatchData(
                        match_id = eventId.toString(),
                        score_home = event.homeScore.display?.toString() ?: "0",
                        score_away = event.awayScore.display?.toString() ?: "0",
                        status = if (status.type == "finished") "finished" else "live",
                        minute = minuteStr,
                        pushTimestampMs = now
                    )
                    
                    LiveScoreCache.save(context, liveData)
                    updateAllWidgets(context) 
                } catch (e: Exception) {
                    Log.e(TAG, "SofaScore autonomous sync failed", e)
                }
            }
        }

        private fun computeFallbackMinute(match: FootballMatch, now: Long): Int {
            val startMs = getMatchStartTimeMillis(match.time_utc, match.date)
            if (startMs <= 0) return 0
            val deltaMin = (now - startMs) / 60_000L
            val rawMinute = deltaMin.toInt()
            
            return when {
                rawMinute <= 45 -> rawMinute.coerceIn(0, 45)
                rawMinute <= 60 -> 45
                else -> (rawMinute - 15).coerceIn(0, 120)
            }
        }

        private fun formatDateOnly(startTimeMillis: Long): String {
            return try {
                val date = Date(startTimeMillis)
                val locale = Locale.getDefault()
                val outSdf = SimpleDateFormat("d MMM", locale)
                var formatted = outSdf.format(date).uppercase()
                if (locale.language != "zh") {
                    formatted = formatted.replace(".", "")
                }
                formatted
            } catch (_: Exception) {
                ""
            }
        }

        private fun isMatchTodayLocal(startTimeMillis: Long): Boolean {
            val calMatch = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
            val calNow = Calendar.getInstance()
            return calMatch.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR) &&
                    calMatch.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
        }

        private fun isMatchTomorrowLocal(startTimeMillis: Long): Boolean {
            val calMatch = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
            val calTomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            return calMatch.get(Calendar.DAY_OF_YEAR) == calTomorrow.get(Calendar.DAY_OF_YEAR) &&
                    calMatch.get(Calendar.YEAR) == calTomorrow.get(Calendar.YEAR)
        }

        private fun utcToLocalTime(timeUtc: String, dateString: String): String {
            return try {
                val utcSdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                utcSdf.timeZone = TimeZone.getTimeZone("UTC")
                val utcDate = utcSdf.parse("$dateString $timeUtc") ?: return timeUtc
                val localSdf = SimpleDateFormat("HH:mm", Locale.US)
                localSdf.timeZone = TimeZone.getDefault()
                localSdf.format(utcDate)
            } catch (_: Exception) {
                timeUtc
            }
        }

        private fun getPendingSelfIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, FootballWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(context, appWidgetId + action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun setOddsText(views: RemoteViews, viewId: Int, value: Double?) {
            if (value != null) {
                views.setTextViewText(viewId, String.format(Locale.US, "%.2f", value))
                views.setViewVisibility(viewId, View.VISIBLE)
            } else {
                views.setViewVisibility(viewId, View.GONE)
            }
        }

        private fun formatLiveMinute(liveData: LiveMatchData, now: Long): String {
            val rawMinute = liveData.minute
            if (rawMinute.contains("PEN") || rawMinute.contains("HT") || rawMinute.contains("+")) {
                return rawMinute
            }
            val cachedMinute = rawMinute.toIntOrNull() ?: return rawMinute
            if (liveData.pushTimestampMs <= 0L) return "$cachedMinute'"
            val deltaMin = ((now - liveData.pushTimestampMs) / 60_000L).toInt()
            if (deltaMin <= 0) return "$cachedMinute'"
            val extrapolated = cachedMinute + deltaMin
            return when {
                cachedMinute <= 45 && extrapolated > 45 -> "45'"
                cachedMinute > 45 && cachedMinute <= 90 && extrapolated > 90 -> "90'"
                else -> "$extrapolated'"
            }
        }

        private fun getLiveBackgroundColor(match: FootballMatch, liveData: LiveMatchData, alphaHex: String): String {
            if (liveData.isFinished) return "#${alphaHex}37474F"
            val favStatus = liveData.getFavoriteStatus(match.favorite, match.home_team)
            return when (favStatus) {
                LiveMatchData.STATUS_WINNING -> "#${alphaHex}1B5E20"
                LiveMatchData.STATUS_DRAW -> "#${alphaHex}F57F17"
                LiveMatchData.STATUS_LOSING -> "#${alphaHex}B71C1C"
                else -> "#${alphaHex}424242"
            }
        }

        private fun getMatchStartTimeMillis(timeUtc: String, date: String): Long {
            if (timeUtc.isEmpty() || date.isEmpty()) return 0L
            val dateTime = "$date $timeUtc"
            val formats = arrayOf("yyyy-MM-dd HH:mm", "dd.MM.yyyy HH:mm", "yyyy/MM/dd HH:mm", "dd-MM-yyyy HH:mm")

            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val parsed = sdf.parse(dateTime)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {}
            }
            return 0L
        }
    }
}
