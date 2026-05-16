package com.batteryalert.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var puzzleEquationText: TextView
    private lateinit var puzzleAnswerInput: EditText
    private lateinit var puzzleErrorText: TextView
    private lateinit var disableAlertsBtn: Button
    private lateinit var puzzleSection: LinearLayout
    private lateinit var reenableSection: LinearLayout
    private lateinit var reenableAlertsBtn: Button
    private lateinit var batteryLevelText: TextView
    private lateinit var dndStatusText: TextView

    private var puzzleX = 0
    private var puzzleY = 0
    private var puzzleZ = 0
    private var puzzleAnswer = 0.0

    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "BatteryAlertPrefs"
        const val KEY_ENABLED = "alerts_enabled"
        const val KEY_REENABLE_AT = "reenable_at"
        const val ACTION_AUTO_REENABLE = "com.batteryalert.app.AUTO_REENABLE"
        private const val AUTO_REENABLE_DELAY_MS = 15 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        batteryLevelText   = findViewById(R.id.batteryLevelText)
        statusText         = findViewById(R.id.statusText)
        dndStatusText      = findViewById(R.id.dndStatusText)
        puzzleEquationText = findViewById(R.id.puzzleEquationText)
        puzzleAnswerInput  = findViewById(R.id.puzzleAnswerInput)
        puzzleErrorText    = findViewById(R.id.puzzleErrorText)
        disableAlertsBtn   = findViewById(R.id.disableAlertsBtn)
        puzzleSection      = findViewById(R.id.puzzleSection)
        reenableSection    = findViewById(R.id.reenableSection)
        reenableAlertsBtn  = findViewById(R.id.reenableAlertsBtn)

        generatePuzzle()

        disableAlertsBtn.setOnClickListener { v ->
            val input = puzzleAnswerInput.text.toString().trim()
            if (input.isEmpty()) {
                puzzleErrorText.text = "✗ Please enter an answer"
                puzzleErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val userAnswer = input.toDoubleOrNull() ?: run {
                puzzleErrorText.text = "✗ Invalid number — enter e.g. 367.3"
                puzzleErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val expected1dp = floor(puzzleAnswer * 10) / 10.0
            val user1dp     = floor(userAnswer * 10) / 10.0

            if (abs(user1dp - expected1dp) < 0.001) {
                puzzleErrorText.visibility = View.GONE
                hideKeyboard(v)
                
                val reenableAt = System.currentTimeMillis() + AUTO_REENABLE_DELAY_MS
                prefs.edit()
                    .putBoolean(KEY_ENABLED, false)
                    .putLong(KEY_REENABLE_AT, reenableAt)
                    .apply()
                
                scheduleAutoReenable(reenableAt)
                stopBatteryService()
                updatePuzzleUI(false)
            } else {
                puzzleErrorText.text = "✗ Incorrect — try again"
                puzzleErrorText.visibility = View.VISIBLE
                puzzleAnswerInput.selectAll()
            }
        }

        reenableAlertsBtn.setOnClickListener {
            prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .remove(KEY_REENABLE_AT)
                .apply()
            
            cancelAutoReenable()
            startBatteryService()
            generatePuzzle()
            updatePuzzleUI(true)
        }

        findViewById<View>(R.id.dndPermissionBtn).setOnClickListener { requestDndPermission() }
        findViewById<View>(R.id.alarmPermissionBtn).setOnClickListener { requestAlarmPermission() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        if (prefs.getBoolean(KEY_ENABLED, true)) {
            startBatteryService()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        
        // If alerts are disabled, check if they should have been re-enabled already
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            val reenableAt = prefs.getLong(KEY_REENABLE_AT, 0L)
            if (reenableAt != 0L && System.currentTimeMillis() >= reenableAt) {
                prefs.edit().putBoolean(KEY_ENABLED, true).remove(KEY_REENABLE_AT).apply()
                startBatteryService()
                updateUI()
            }
        }
    }

    private fun generatePuzzle() {
        puzzleX = 1000 + Random.nextInt(9000)
        puzzleY = 1000 + Random.nextInt(9000)
        puzzleZ = 1000 + Random.nextInt(9000)
        puzzleAnswer = sqrt((puzzleX.toLong() * puzzleY + puzzleZ).toDouble())
        puzzleEquationText.text = "sqrt($puzzleX × $puzzleY + $puzzleZ) = ?"
    }

    private fun updatePuzzleUI(alertsEnabled: Boolean) {
        if (alertsEnabled) {
            statusText.text = "● ACTIVE"
            statusText.setTextColor(getColor(R.color.green))
            puzzleSection.visibility = View.VISIBLE
            reenableSection.visibility = View.GONE
            puzzleAnswerInput.setText("")
            puzzleErrorText.visibility = View.GONE
            puzzleEquationText.text = "sqrt($puzzleX × $puzzleY + $puzzleZ) = ?"
        } else {
            statusText.text = "● DISABLED"
            statusText.setTextColor(getColor(R.color.red))
            puzzleSection.visibility = View.GONE
            reenableSection.visibility = View.VISIBLE
        }
    }

    private fun updateUI() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevelText.text = "${(level / scale.toFloat() * 100).toInt()}%"
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm?.isNotificationPolicyAccessGranted == true) {
            dndStatusText.text = "✓ Do Not Disturb Override: GRANTED"
            dndStatusText.setTextColor(getColor(R.color.green))
        } else {
            dndStatusText.text = "✗ Do Not Disturb Override: NOT GRANTED — Tap to grant"
            dndStatusText.setTextColor(getColor(R.color.red))
        }

        updatePuzzleUI(prefs.getBoolean(KEY_ENABLED, true))
    }

    private fun startBatteryService() {
        val serviceIntent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopBatteryService() {
        stopService(Intent(this, BatteryMonitorService::class.java))
    }

    private fun requestDndPermission() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && !nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Toast.makeText(this, "Please grant Do Not Disturb access for Battery Alert", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "DND access already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Please grant Exact Alarm access", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Exact Alarm access already granted!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Not required for your Android version", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun scheduleAutoReenable(timeMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoReenableReceiver::class.java).apply {
            action = ACTION_AUTO_REENABLE
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi)
            } else {
                // Fallback to non-exact if permission not granted
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi)
        }
    }

    private fun cancelAutoReenable() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoReenableReceiver::class.java).apply {
            action = ACTION_AUTO_REENABLE
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}
