package com.shrewd.bet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
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
        private const val DEFAULT_ODDS_THRESHOLD = 1.8f
        private const val DEFAULT_WIDGET_TRANSPARENCY = 70 // 70%

        fun isSetupCompleted(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_COMPLETED, false)
        }

        fun areNotificationsEnabled(context: Context): Boolean {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
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
        val btnNotificationSettings = findViewById<Button>(R.id.btnNotificationSettings)
        val btnDone = findViewById<Button>(R.id.btnDone)
        
        val tvThresholdValue = findViewById<android.widget.TextView>(R.id.tvThresholdValue)
        val seekBarThreshold = findViewById<android.widget.SeekBar>(R.id.seekBarThreshold)
        
        val tvTransparencyValue = findViewById<android.widget.TextView>(R.id.tvTransparencyValue)
        val seekBarTransparency = findViewById<android.widget.SeekBar>(R.id.seekBarTransparency)

        val savedThreshold = getFavoriteOddsThreshold(this)
        val progress = ((savedThreshold - 1.1f) * 100).toInt().coerceIn(0, 70)
        seekBarThreshold.progress = progress
        updateThresholdText(tvThresholdValue, savedThreshold)

        seekBarThreshold.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = 1.1f + (progress / 100f)
                updateThresholdText(tvThresholdValue, threshold)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val threshold = 1.1f + (seekBar?.progress ?: 0) / 100f
                prefs.edit { putFloat(KEY_ODDS_THRESHOLD, threshold) }
                FootballWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        })

        val savedTransparency = getWidgetTransparency(this)
        seekBarTransparency.progress = savedTransparency
        updateTransparencyText(tvTransparencyValue, savedTransparency)

        seekBarTransparency.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateTransparencyText(tvTransparencyValue, progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val transparency = seekBar?.progress ?: DEFAULT_WIDGET_TRANSPARENCY
                prefs.edit { putInt(KEY_WIDGET_TRANSPARENCY, transparency) }
                FootballWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        })

        btnNotificationSettings.setOnClickListener {
            openNotificationSettings(this)
        }

        btnDone.setOnClickListener {
            prefs.edit { putBoolean(KEY_SETUP_COMPLETED, true) }
            finish()
        }
    }

    private fun updateThresholdText(textView: android.widget.TextView, threshold: Float) {
        textView.text = getString(R.string.current_threshold_format, threshold)
    }

    private fun updateTransparencyText(textView: android.widget.TextView, transparency: Int) {
        textView.text = getString(R.string.transparency_format, transparency)
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
        } catch (e: Exception) {
            val fallbackIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }
}
