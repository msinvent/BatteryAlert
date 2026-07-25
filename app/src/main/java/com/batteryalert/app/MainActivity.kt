package com.batteryalert.app

import android.app.AlarmManager
import android.app.TimePickerDialog
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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var pause30mBtn: Button
    private lateinit var pause1hBtn: Button
    private lateinit var pause2hBtn: Button
    private lateinit var rootScroll: View
    private lateinit var disabledOverlay: View
    private lateinit var batteryLevelText: TextView
    private lateinit var dndStatusText: TextView
    private lateinit var fsiStatusText: TextView
    private lateinit var thresholdHighInput: EditText
    private lateinit var thresholdMidInput: EditText
    private lateinit var thresholdLowInput: EditText
    private lateinit var thresholdErrorText: TextView
    private lateinit var sirenHighBtn: Button
    private lateinit var sirenMidBtn: Button
    private lateinit var sirenLowBtn: Button
    private lateinit var infoNoteText: TextView

    private var sirenHighSec = 0
    private var sirenMidSec = 0
    private var sirenLowSec = 0

    private lateinit var deepSleepSwitch: Switch
    private lateinit var deepSleepStartBtn: Button
    private lateinit var deepSleepEndBtn: Button
    private var deepSleep = DeepSleepWindow.DEFAULT

    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "BatteryAlertPrefs"
        const val KEY_ENABLED = "alerts_enabled"
        const val KEY_RESUME_AT = "resume_at"
        const val ACTION_AUTO_RESUME = "com.batteryalert.app.AUTO_RESUME"
        private const val KEY_ALARM_PROMPTED = "exact_alarm_prompted"
        private const val HOUR_MS = 60 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        batteryLevelText   = findViewById(R.id.batteryLevelText)
        statusText         = findViewById(R.id.statusText)
        dndStatusText      = findViewById(R.id.dndStatusText)
        fsiStatusText      = findViewById(R.id.fsiStatusText)
        pause30mBtn        = findViewById(R.id.pause30mBtn)
        pause1hBtn         = findViewById(R.id.pause1hBtn)
        pause2hBtn         = findViewById(R.id.pause2hBtn)
        rootScroll         = findViewById(R.id.rootScroll)
        disabledOverlay    = findViewById(R.id.disabledOverlay)
        thresholdHighInput = findViewById(R.id.thresholdHighInput)
        thresholdMidInput  = findViewById(R.id.thresholdMidInput)
        thresholdLowInput  = findViewById(R.id.thresholdLowInput)
        thresholdErrorText = findViewById(R.id.thresholdErrorText)
        sirenHighBtn       = findViewById(R.id.sirenHighBtn)
        sirenMidBtn        = findViewById(R.id.sirenMidBtn)
        sirenLowBtn        = findViewById(R.id.sirenLowBtn)
        infoNoteText       = findViewById(R.id.infoNoteText)

        deepSleepSwitch   = findViewById(R.id.deepSleepSwitch)
        deepSleepStartBtn = findViewById(R.id.deepSleepStartBtn)
        deepSleepEndBtn   = findViewById(R.id.deepSleepEndBtn)

        setupThresholdEditor()
        setupDeepSleepEditor()

        pause30mBtn.setOnClickListener { pauseAlerts(HOUR_MS / 2) }
        pause1hBtn.setOnClickListener { pauseAlerts(1 * HOUR_MS) }
        pause2hBtn.setOnClickListener { pauseAlerts(2 * HOUR_MS) }

        findViewById<Button>(R.id.enableAlertsBtn).setOnClickListener {
            prefs.edit {
                putBoolean(KEY_ENABLED, true)
                    .remove(KEY_RESUME_AT)
            }

            cancelAutoResume()
            startBatteryService()
            updateAlertsUI(true)
        }

        findViewById<View>(R.id.dndPermissionBtn).setOnClickListener { requestDndPermission() }
        findViewById<View>(R.id.alarmPermissionBtn).setOnClickListener { requestAlarmPermission() }
        fsiStatusText.setOnClickListener { requestFullScreenIntentPermission() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        maybePromptExactAlarm()

        if (prefs.getBoolean(KEY_ENABLED, true)) {
            startBatteryService()
        }
    }

    // SCHEDULE_EXACT_ALARM is default-denied on Android 14+; without it the
    // check chain falls back to inexact alarms that drift in Doze. Prompt once.
    private fun maybePromptExactAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (am.canScheduleExactAlarms()) return
        if (prefs.getBoolean(KEY_ALARM_PROMPTED, false)) return
        prefs.edit { putBoolean(KEY_ALARM_PROMPTED, true) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exact_alarm_prompt_title))
            .setMessage(getString(R.string.exact_alarm_prompt_message))
            .setPositiveButton(getString(R.string.exact_alarm_prompt_grant)) { _, _ -> requestAlarmPermission() }
            .setNegativeButton(getString(R.string.exact_alarm_prompt_later), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateUI()

        // Self-heal the check chain: a force-stop or exact-alarm revocation
        // cancels the scheduled check, so every app open re-bootstraps it.
        if (prefs.getBoolean(KEY_ENABLED, true)) {
            BatteryCheck.runNow(this)
        }

        // If alerts are disabled, check if they should have been resumed already
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            val resumeAt = prefs.getLong(KEY_RESUME_AT, 0L)
            if (resumeAt != 0L && System.currentTimeMillis() >= resumeAt) {
                prefs.edit { putBoolean(KEY_ENABLED, true).remove(KEY_RESUME_AT) }
                startBatteryService()
                updateUI()
            }
        }
    }

    private fun setupThresholdEditor() {
        val config = BatteryCheck.loadConfig(this)
        thresholdHighInput.setText(config.high.toString())
        thresholdMidInput.setText(config.mid.toString())
        thresholdLowInput.setText(config.low.toString())
        sirenHighSec = config.highSirenSec
        sirenMidSec = config.midSirenSec
        sirenLowSec = config.lowSirenSec
        refreshSirenButtons()
        updateInfoNote()

        sirenHighBtn.setOnClickListener {
            sirenHighSec = ThresholdConfig.nextSirenChoice(sirenHighSec)
            refreshSirenButtons()
        }
        sirenMidBtn.setOnClickListener {
            sirenMidSec = ThresholdConfig.nextSirenChoice(sirenMidSec)
            refreshSirenButtons()
        }
        sirenLowBtn.setOnClickListener {
            sirenLowSec = ThresholdConfig.nextSirenChoice(sirenLowSec)
            refreshSirenButtons()
        }

        findViewById<Button>(R.id.saveThresholdsBtn).setOnClickListener { v ->
            val high = thresholdHighInput.text.toString().toIntOrNull()
            val mid = thresholdMidInput.text.toString().toIntOrNull()
            val low = thresholdLowInput.text.toString().toIntOrNull()
            val newConfig = if (high != null && mid != null && low != null) {
                ThresholdConfig(high, mid, low, sirenHighSec, sirenMidSec, sirenLowSec)
            } else null

            if (newConfig == null || !newConfig.isValid()) {
                thresholdErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            thresholdErrorText.visibility = View.GONE
            hideKeyboard(v)
            BatteryCheck.saveConfig(this, newConfig)
            updateInfoNote()
            Toast.makeText(this, getString(R.string.toast_thresholds_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDeepSleepEditor() {
        deepSleep = BatteryCheck.loadDeepSleep(this)
        deepSleepSwitch.isChecked = deepSleep.enabled
        refreshDeepSleepButtons()

        deepSleepSwitch.setOnCheckedChangeListener { _, isChecked ->
            deepSleep = deepSleep.copy(enabled = isChecked)
            BatteryCheck.saveDeepSleep(this, deepSleep)
            updateInfoNote()
        }
        deepSleepStartBtn.setOnClickListener {
            pickTime(deepSleep.startMinutes) { minutes ->
                deepSleep = deepSleep.copy(startMinutes = minutes)
                BatteryCheck.saveDeepSleep(this, deepSleep)
                refreshDeepSleepButtons()
                updateInfoNote()
            }
        }
        deepSleepEndBtn.setOnClickListener {
            pickTime(deepSleep.endMinutes) { minutes ->
                deepSleep = deepSleep.copy(endMinutes = minutes)
                BatteryCheck.saveDeepSleep(this, deepSleep)
                refreshDeepSleepButtons()
                updateInfoNote()
            }
        }
        updateInfoNote()
    }

    private fun pickTime(currentMinutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            currentMinutes / 60, currentMinutes % 60, true
        ).show()
    }

    private fun refreshDeepSleepButtons() {
        deepSleepStartBtn.text =
            getString(R.string.time_format, deepSleep.startMinutes / 60, deepSleep.startMinutes % 60)
        deepSleepEndBtn.text =
            getString(R.string.time_format, deepSleep.endMinutes / 60, deepSleep.endMinutes % 60)
    }

    private fun refreshSirenButtons() {
        sirenHighBtn.text = getString(R.string.siren_length_format, sirenHighSec)
        sirenMidBtn.text = getString(R.string.siren_length_format, sirenMidSec)
        sirenLowBtn.text = getString(R.string.siren_length_format, sirenLowSec)
    }

    private fun updateInfoNote() {
        val config = BatteryCheck.loadConfig(this)
        val base = getString(R.string.info_note_format, config.high + BatteryAlarmDecider.RESET_MARGIN)
        infoNoteText.text = if (deepSleep.enabled) {
            getString(
                R.string.info_note_sleep_format, base,
                deepSleep.startMinutes / 60, deepSleep.startMinutes % 60,
                deepSleep.endMinutes / 60, deepSleep.endMinutes % 60
            )
        } else {
            base
        }
    }

    private fun pauseAlerts(durationMs: Long) {
        val resumeAt = System.currentTimeMillis() + durationMs
        prefs.edit {
            putBoolean(KEY_ENABLED, false)
                .putLong(KEY_RESUME_AT, resumeAt)
        }
        scheduleAutoResume(resumeAt)
        stopBatteryService()
        updateAlertsUI(false)
    }

    private fun updateAlertsUI(alertsEnabled: Boolean) {
        // Paused state replaces the whole UI: red-washed backdrop with one
        // big circular ENABLE button (disabledOverlay in the layout).
        rootScroll.visibility = if (alertsEnabled) View.VISIBLE else View.GONE
        disabledOverlay.visibility = if (alertsEnabled) View.GONE else View.VISIBLE

        if (alertsEnabled) {
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
        } else {
            statusText.text = getString(R.string.status_disabled)
            statusText.setTextColor(getColor(R.color.red))
        }
    }

    private fun updateUI() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevelText.text = getString(R.string.battery_level_format, (level / scale.toFloat() * 100).toInt())
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm?.isNotificationPolicyAccessGranted == true) {
            dndStatusText.text = getString(R.string.dnd_granted)
            dndStatusText.setTextColor(getColor(R.color.green))
        } else {
            dndStatusText.text = getString(R.string.dnd_not_granted)
            dndStatusText.setTextColor(getColor(R.color.red))
        }

        // Play or the user can revoke USE_FULL_SCREEN_INTENT (targetSdk 34+);
        // without it the lock-screen alert silently degrades to a heads-up.
        if (nm?.canUseFullScreenIntent() == true) {
            fsiStatusText.text = getString(R.string.fsi_granted)
            fsiStatusText.setTextColor(getColor(R.color.green))
        } else {
            fsiStatusText.text = getString(R.string.fsi_not_granted)
            fsiStatusText.setTextColor(getColor(R.color.red))
        }

        updateAlertsUI(prefs.getBoolean(KEY_ENABLED, true))
    }

    private fun startBatteryService() {
        BatteryCheck.runNow(this)
    }

    private fun stopBatteryService() {
        BatteryCheck.cancel(this)
        // Also kill an actively ringing siren.
        stopService(Intent(this, BatteryAlarmService::class.java))
    }

    private fun requestDndPermission() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && !nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Toast.makeText(this, getString(R.string.toast_dnd_access), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_dnd_already_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
                Toast.makeText(this, getString(R.string.toast_alarm_access), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_alarm_already_granted), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_alarm_not_required), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestFullScreenIntentPermission() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm?.canUseFullScreenIntent() == true) return
        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = "package:$packageName".toUri()
        })
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun scheduleAutoResume(timeMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoResumeReceiver::class.java).apply {
            action = ACTION_AUTO_RESUME
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

    private fun cancelAutoResume() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoResumeReceiver::class.java).apply {
            action = ACTION_AUTO_RESUME
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}
