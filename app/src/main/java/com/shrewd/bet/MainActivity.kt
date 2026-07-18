package com.shrewd.bet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "football_widget_prefs"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_ODDS_THRESHOLD = "odds_threshold"
        private const val KEY_WIDGET_TRANSPARENCY = "widget_transparency"
        private const val KEY_DAYS_AHEAD = "widget_days_ahead"
        
        private const val DEFAULT_ODDS_THRESHOLD = 1.8f
        private const val DEFAULT_WIDGET_TRANSPARENCY = 70 // 70%
        private const val DEFAULT_DAYS_AHEAD = 1

        fun areNotificationsEnabled(context: Context): Boolean {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notificationManager.areNotificationsEnabled()
            } else {
                true // Fallback for old versions
            }
        }

        fun getFavoriteOddsThreshold(context: Context): Float {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_ODDS_THRESHOLD, DEFAULT_ODDS_THRESHOLD)
        }

        fun getWidgetTransparency(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_WIDGET_TRANSPARENCY, DEFAULT_WIDGET_TRANSPARENCY)
        }

        fun getDaysAhead(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DAYS_AHEAD, DEFAULT_DAYS_AHEAD)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        com.shrewd.bet.fcm.MatchUpdateFirebaseService.createNotificationChannels(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 1. Odds Threshold (Favorite)
        val tvThresholdValue = findViewById<TextView>(R.id.tvThresholdValue)
        val seekBarThreshold = findViewById<SeekBar>(R.id.seekBarThreshold)
        val savedThreshold = getFavoriteOddsThreshold(this)
        seekBarThreshold.progress = ((savedThreshold - 1.1f) * 100).toInt().coerceIn(0, 70)
        updateThresholdText(tvThresholdValue, savedThreshold)
        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateThresholdText(tvThresholdValue, 1.1f + (progress / 100f))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val threshold = 1.1f + (seekBar?.progress ?: 0) / 100f
                prefs.edit(commit = true) { putFloat(KEY_ODDS_THRESHOLD, threshold) }
                FootballWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        })

        // 2. Days Ahead
        val tvDaysAheadValue = findViewById<TextView>(R.id.tvDaysAheadValue)
        val seekBarDaysAhead = findViewById<SeekBar>(R.id.seekBarDaysAhead)
        val savedDaysAhead = getDaysAhead(this)
        seekBarDaysAhead.progress = (savedDaysAhead - 1).coerceIn(0, 6)
        updateDaysAheadText(tvDaysAheadValue, savedDaysAhead)
        seekBarDaysAhead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDaysAheadText(tvDaysAheadValue, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val days = (seekBar?.progress ?: 0) + 1
                prefs.edit(commit = true) { putInt(KEY_DAYS_AHEAD, days) }
                FootballWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        })

        // 3. Transparency
        val tvTransparencyValue = findViewById<TextView>(R.id.tvTransparencyValue)
        val seekBarTransparency = findViewById<SeekBar>(R.id.seekBarTransparency)
        val savedTransparency = getWidgetTransparency(this)
        seekBarTransparency.progress = savedTransparency
        updateTransparencyText(tvTransparencyValue, savedTransparency)
        seekBarTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTransparencyText(tvTransparencyValue, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val transparency = seekBar?.progress ?: DEFAULT_WIDGET_TRANSPARENCY
                prefs.edit(commit = true) { putInt(KEY_WIDGET_TRANSPARENCY, transparency) }
                FootballWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        })

        findViewById<Button>(R.id.btnNotificationSettings).setOnClickListener {
            openNotificationSettings(this)
        }

        findViewById<Button>(R.id.btnDone).setOnClickListener {
            prefs.edit { putBoolean(KEY_SETUP_COMPLETED, true) }
            finish()
        }
    }

    private fun updateThresholdText(textView: TextView, threshold: Float) {
        textView.text = getString(R.string.current_threshold_format, threshold)
    }

    private fun updateTransparencyText(textView: TextView, transparency: Int) {
        textView.text = getString(R.string.transparency_format, transparency)
    }

    private fun updateDaysAheadText(textView: TextView, days: Int) {
        textView.text = getString(R.string.days_ahead_format, days)
    }

    private fun openNotificationSettings(context: Context) {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("app_package", context.packageName)
                    putExtra("app_uid", context.applicationInfo.uid)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }
}
