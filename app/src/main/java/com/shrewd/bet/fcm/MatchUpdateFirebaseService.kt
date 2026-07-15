package com.shrewd.bet.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shrewd.bet.FootballMatch
import com.shrewd.bet.FootballWidgetProvider
import com.shrewd.bet.MainActivity
import com.shrewd.bet.MomentumActivity
import com.shrewd.bet.R
import com.shrewd.bet.model.LiveMatchData
import com.shrewd.bet.model.LiveScoreCache
import com.shrewd.bet.repository.MatchRepository

class MatchUpdateFirebaseService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        Log.d(TAG, "Received data message: $data")

        val matchId = data["match_id"]
        val eventType = data["event_type"] ?: ""
        val scoreHome = data["score_home"]
        val scoreAway = data["score_away"]
        val status = data["status"]
        val minute = data["minute"]

        if (matchId != null && scoreHome != null && scoreAway != null && status != null) {
            val liveData = LiveMatchData(
                match_id = matchId,
                score_home = scoreHome,
                score_away = scoreAway,
                status = status,
                minute = minute ?: "",
                pushTimestampMs = System.currentTimeMillis()
            )

            // Проверяем, есть ли уже данные по этому матчу в кэше
            val isFirstUpdate = LiveScoreCache.get(applicationContext, matchId) == null

            // Сохраняем live-счёт в кэш ВСЕГДА, чтобы данные были актуальны при смене фильтра
            LiveScoreCache.save(applicationContext, liveData)

            // Делаем уборку перед обновлением виджетов
            FootballWidgetProvider.cleanupStaleMatches(applicationContext)

            val repository = MatchRepository(applicationContext)
            val filteredMatches = repository.loadFilteredMatches()
            val match = filteredMatches.find {
                it.sofascore_event_id?.toString() == matchId
            }
            val isMatchVisible = match != null

            val isImportantEvent = eventType == "goal" || eventType == "favorite_trouble" || isFirstUpdate
            
            // Обновляем виджеты. 
            // Фокусируемся только если событие важное И матч виден пользователю.
            FootballWidgetProvider.updateAllWidgets(
                applicationContext, 
                if (isImportantEvent && isMatchVisible) liveData.match_id else null
            )

            // Уведомление — только если включены и матч виден
            if (MainActivity.areNotificationsEnabled(applicationContext) && isMatchVisible) {
                when {
                    eventType == "goal" || eventType == "favorite_trouble" -> {
                        sendNotification(liveData, eventType, match)
                    }
                    isFirstUpdate -> {
                        sendNotification(liveData, "match_started", match)
                    }
                }
            } else if (!isMatchVisible) {
                Log.d(TAG, "Notification skipped: match $matchId not visible (filtered out or not in database)")
            }

            Log.d(TAG, "Live score saved and widgets updated: ${liveData.scoreText} ($status, event=$eventType, first=$isFirstUpdate)")
        } else {
            Log.w(TAG, "Incomplete match data in push: $data")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
    }

    private fun sendNotification(liveData: LiveMatchData, eventType: String, match: FootballMatch?) {
        val intent = Intent(this, MomentumActivity::class.java).apply {
            putExtra(MomentumActivity.EXTRA_EVENT_ID, liveData.match_id.toLongOrNull() ?: -1L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            liveData.match_id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val teams = if (match != null) "${match.home_team} ${liveData.scoreText} ${match.away_team}" else liveData.scoreText
        val minuteSuffix = if (liveData.minute.isNotEmpty()) " (${liveData.minute}')" else ""

        val favStatus = match?.let {
            liveData.getFavoriteStatus(it.favorite, it.home_team)
        }

        val title: String
        val text: String

        when (eventType) {
            "match_started" -> {
                title = "\u23F1\uFE0F " + getString(R.string.match_started)
                text = "$teams"
            }
            "goal" -> {
                title = "\u26BD " + getString(R.string.goal)
                text = "$teams$minuteSuffix"
            }
            "favorite_trouble" -> {
                when (favStatus) {
                    LiveMatchData.STATUS_DRAW -> {
                        title = "\u26A0\uFE0F " + getString(R.string.fav_draw)
                        text = "${match.favorite} | $teams$minuteSuffix"
                    }
                    LiveMatchData.STATUS_LOSING -> {
                        title = "\uD83D\uDD34 " + getString(R.string.fav_losing)
                        text = "${match.favorite} | $teams$minuteSuffix"
                    }
                    else -> {
                        // fallback — shouldn't happen for favorite_trouble, but safe
                        title = "\u26A0\uFE0F " + getString(R.string.fav_trouble)
                        text = "$teams$minuteSuffix"
                    }
                }
            }
            else -> {
                title = getString(R.string.match_update)
                text = "$teams$minuteSuffix"
            }
        }

        val channelId = when {
            eventType == "match_started" -> CHANNEL_MATCH_STARTED
            eventType == "goal" -> CHANNEL_GOAL
            favStatus == LiveMatchData.STATUS_DRAW -> CHANNEL_FAV_DRAW
            favStatus == LiveMatchData.STATUS_LOSING -> CHANNEL_FAV_LOSING
            else -> CHANNEL_GOAL // fallback
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(liveData.match_id.hashCode(), notification)
    }

    companion object {
        private const val TAG = "MatchUpdateFCM"
        private const val CHANNEL_GOAL = "match_goal"
        private const val CHANNEL_FAV_DRAW = "match_favorite_draw"
        private const val CHANNEL_FAV_LOSING = "match_favorite_losing"
        private const val CHANNEL_MATCH_STARTED = "match_started"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(NotificationChannel(
                    CHANNEL_GOAL,
                    context.getString(R.string.goal),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.goal)
                })
                manager?.createNotificationChannel(NotificationChannel(
                    CHANNEL_FAV_DRAW,
                    context.getString(R.string.fav_draw),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.fav_draw)
                })
                manager?.createNotificationChannel(NotificationChannel(
                    CHANNEL_FAV_LOSING,
                    context.getString(R.string.fav_losing),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.fav_losing)
                })
                manager?.createNotificationChannel(NotificationChannel(
                    CHANNEL_MATCH_STARTED,
                    context.getString(R.string.match_started),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.match_started)
                })
            }
        }
    }
}
