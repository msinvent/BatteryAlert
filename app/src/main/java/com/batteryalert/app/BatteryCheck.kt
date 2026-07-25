package com.batteryalert.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.edit

/**
 * Replaces the old persistent monitoring service: each check reads the sticky
 * battery intent, feeds the decider (state persisted in prefs), rings
 * BatteryAlarmService when a threshold trips, and schedules the next check
 * with a battery-adaptive delay. No long-lived process, no specialUse FGS.
 */
object BatteryCheck {

    private const val TAG = "BatteryAlertCheck"
    private const val REQUEST_CODE_CHECK = 1
    const val ACTION_BATTERY_CHECK = "com.batteryalert.app.BATTERY_CHECK"

    private const val KEY_ALERT_20_FIRED = "alert20_fired"
    private const val KEY_ALERT_15_FIRED = "alert15_fired"
    private const val KEY_ALERT_10_FIRED = "alert10_fired"

    private const val INTERVAL_RELAXED_MS = 30 * 60 * 1000L
    private const val INTERVAL_WATCHFUL_MS = 15 * 60 * 1000L
    private const val INTERVAL_NEAR_THRESHOLD_MS = 10 * 60 * 1000L
    private const val INTERVAL_CRITICAL_MS = 5 * 60 * 1000L

    fun runNow(context: Context) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val batteryPct = (level / scale.toFloat() * 100).toInt()

        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(MainActivity.KEY_ENABLED, true)

        val decider = BatteryAlarmDecider(
            alert20Fired = prefs.getBoolean(KEY_ALERT_20_FIRED, false),
            alert15Fired = prefs.getBoolean(KEY_ALERT_15_FIRED, false),
            alert10Fired = prefs.getBoolean(KEY_ALERT_10_FIRED, false)
        )
        val decision = decider.onBatteryEvent(batteryPct, isCharging, enabled, isAlerting = false)
        prefs.edit {
            putBoolean(KEY_ALERT_20_FIRED, decider.alert20Fired)
                .putBoolean(KEY_ALERT_15_FIRED, decider.alert15Fired)
                .putBoolean(KEY_ALERT_10_FIRED, decider.alert10Fired)
        }

        Log.d(TAG, "Check: $batteryPct% charging=$isCharging enabled=$enabled -> $decision")

        if (decision is BatteryAlarmDecider.Decision.Trigger) {
            startAlarm(context, decision)
        }

        if (enabled) {
            schedule(context, nextDelayMs(batteryPct, isCharging))
        }
    }

    fun schedule(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        val pi = checkPendingIntent(context)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(checkPendingIntent(context))
    }

    private fun startAlarm(context: Context, decision: BatteryAlarmDecider.Decision.Trigger) {
        val intent = Intent(context, BatteryAlarmService::class.java).apply {
            putExtra(BatteryAlarmService.EXTRA_BATTERY_PCT, decision.batteryPct)
            putExtra(BatteryAlarmService.EXTRA_DURATION_MS, decision.threshold.durationMs)
            putExtra(BatteryAlarmService.EXTRA_MESSAGE, decision.message)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start alarm service: ${e.message}")
        }
    }

    private fun checkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE_CHECK,
            Intent(context, BatteryCheckReceiver::class.java).setAction(ACTION_BATTERY_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextDelayMs(batteryPct: Int, isCharging: Boolean): Long = when {
        isCharging -> INTERVAL_RELAXED_MS
        batteryPct > 50 -> INTERVAL_RELAXED_MS
        batteryPct > 30 -> INTERVAL_WATCHFUL_MS
        batteryPct > 20 -> INTERVAL_NEAR_THRESHOLD_MS
        else -> INTERVAL_CRITICAL_MS
    }
}
