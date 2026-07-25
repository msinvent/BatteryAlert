package com.batteryalert.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.edit
import java.util.Calendar

data class BatteryReading(val percent: Int, val isCharging: Boolean)

/**
 * The domain façade. Owns the whole alert lifecycle outside the siren itself:
 * scheduled checks (exact alarms, adaptive interval), the pause/resume state
 * transition and its auto-resume alarm, config + deep-sleep persistence, and
 * the decider's fired-flag state across process death. No long-lived process,
 * no specialUse FGS. UI and receivers call in; only BatteryAlarmService is
 * launched out.
 */
object BatteryCheck {

    private const val TAG = "BatteryAlertCheck"
    private const val REQUEST_CODE_CHECK = 1
    private const val REQUEST_CODE_AUTO_RESUME = 2
    const val ACTION_BATTERY_CHECK = "com.batteryalert.app.BATTERY_CHECK"
    const val ACTION_AUTO_RESUME = "com.batteryalert.app.AUTO_RESUME"

    private const val KEY_HIGH_FIRED = "alert_high_fired"
    private const val KEY_MID_FIRED = "alert_mid_fired"
    private const val KEY_LOW_FIRED = "alert_low_fired"

    private const val KEY_THRESHOLD_HIGH = "threshold_high"
    private const val KEY_THRESHOLD_MID = "threshold_mid"
    private const val KEY_THRESHOLD_LOW = "threshold_low"
    private const val KEY_SIREN_HIGH_SEC = "siren_high_sec"
    private const val KEY_SIREN_MID_SEC = "siren_mid_sec"
    private const val KEY_SIREN_LOW_SEC = "siren_low_sec"

    private const val KEY_SLEEP_ENABLED = "sleep_enabled"
    private const val KEY_SLEEP_START_MIN = "sleep_start_min"
    private const val KEY_SLEEP_END_MIN = "sleep_end_min"

    private const val INTERVAL_RELAXED_MS = 30 * 60 * 1000L
    private const val INTERVAL_WATCHFUL_MS = 15 * 60 * 1000L
    private const val INTERVAL_NEAR_THRESHOLD_MS = 10 * 60 * 1000L
    private const val INTERVAL_CRITICAL_MS = 5 * 60 * 1000L
    private const val MARGIN_RELAXED = 30
    private const val MARGIN_WATCHFUL = 10

    // ── Battery ──

    fun readBattery(context: Context): BatteryReading? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryReading(
            percent = (level / scale.toFloat() * 100).toInt(),
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
        )
    }

    // ── Pause / resume (the only place this transition lives) ──

    fun pause(context: Context, durationMs: Long) {
        val resumeAt = System.currentTimeMillis() + durationMs
        Prefs.get(context).edit {
            putBoolean(Prefs.KEY_ENABLED, false)
                .putLong(Prefs.KEY_RESUME_AT, resumeAt)
        }
        setAlarm(context, resumeAt, autoResumePendingIntent(context))
        cancel(context)
        // Also kill an actively ringing siren.
        context.stopService(Intent(context, BatteryAlarmService::class.java))
        Log.d(TAG, "Paused until $resumeAt")
    }

    fun resume(context: Context) {
        Prefs.get(context).edit {
            putBoolean(Prefs.KEY_ENABLED, true)
                .remove(Prefs.KEY_RESUME_AT)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(autoResumePendingIntent(context))
        runNow(context)
    }

    /** True when a pause has expired but the auto-resume alarm never fired. */
    fun resumeOverdue(context: Context): Boolean {
        val prefs = Prefs.get(context)
        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) return false
        val resumeAt = prefs.getLong(Prefs.KEY_RESUME_AT, 0L)
        return resumeAt != 0L && System.currentTimeMillis() >= resumeAt
    }

    // ── Config persistence ──

    fun loadConfig(context: Context): ThresholdConfig {
        val prefs = Prefs.get(context)
        val default = ThresholdConfig.DEFAULT
        val config = ThresholdConfig(
            high = prefs.getInt(KEY_THRESHOLD_HIGH, default.high),
            mid = prefs.getInt(KEY_THRESHOLD_MID, default.mid),
            low = prefs.getInt(KEY_THRESHOLD_LOW, default.low),
            highSirenSec = prefs.getInt(KEY_SIREN_HIGH_SEC, default.highSirenSec),
            midSirenSec = prefs.getInt(KEY_SIREN_MID_SEC, default.midSirenSec),
            lowSirenSec = prefs.getInt(KEY_SIREN_LOW_SEC, default.lowSirenSec)
        )
        return if (config.isValid()) config else default
    }

    /** Persists a new config, re-arms all thresholds, and re-evaluates now. */
    fun saveConfig(context: Context, config: ThresholdConfig) {
        require(config.isValid()) { "invalid threshold config: $config" }
        Prefs.get(context).edit {
            putInt(KEY_THRESHOLD_HIGH, config.high)
                .putInt(KEY_THRESHOLD_MID, config.mid)
                .putInt(KEY_THRESHOLD_LOW, config.low)
                .putInt(KEY_SIREN_HIGH_SEC, config.highSirenSec)
                .putInt(KEY_SIREN_MID_SEC, config.midSirenSec)
                .putInt(KEY_SIREN_LOW_SEC, config.lowSirenSec)
                .remove(KEY_HIGH_FIRED)
                .remove(KEY_MID_FIRED)
                .remove(KEY_LOW_FIRED)
        }
        runNow(context)
    }

    fun loadDeepSleep(context: Context): DeepSleepWindow {
        val prefs = Prefs.get(context)
        val default = DeepSleepWindow.DEFAULT
        val window = DeepSleepWindow(
            enabled = prefs.getBoolean(KEY_SLEEP_ENABLED, default.enabled),
            startMinutes = prefs.getInt(KEY_SLEEP_START_MIN, default.startMinutes),
            endMinutes = prefs.getInt(KEY_SLEEP_END_MIN, default.endMinutes)
        )
        return if (window.isValid()) window else default
    }

    fun saveDeepSleep(context: Context, window: DeepSleepWindow) {
        Prefs.get(context).edit {
            putBoolean(KEY_SLEEP_ENABLED, window.enabled)
                .putInt(KEY_SLEEP_START_MIN, window.startMinutes)
                .putInt(KEY_SLEEP_END_MIN, window.endMinutes)
        }
    }

    // ── The check loop ──

    fun runNow(context: Context) {
        val reading = readBattery(context) ?: return
        val prefs = Prefs.get(context)
        val enabled = prefs.getBoolean(Prefs.KEY_ENABLED, true)
        val config = loadConfig(context)

        // During deep sleep the decider sees "disabled": no alarms, but flags
        // stay unconsumed, so the deepest crossed threshold fires on the first
        // check after the window ends.
        val now = Calendar.getInstance()
        val minutesOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val asleep = loadDeepSleep(context).contains(minutesOfDay)

        val decider = BatteryAlarmDecider(
            config = config,
            highFired = prefs.getBoolean(KEY_HIGH_FIRED, false),
            midFired = prefs.getBoolean(KEY_MID_FIRED, false),
            lowFired = prefs.getBoolean(KEY_LOW_FIRED, false)
        )
        val decision = decider.onBatteryEvent(
            reading.percent, reading.isCharging, enabled && !asleep, isAlerting = false
        )
        prefs.edit {
            putBoolean(KEY_HIGH_FIRED, decider.highFired)
                .putBoolean(KEY_MID_FIRED, decider.midFired)
                .putBoolean(KEY_LOW_FIRED, decider.lowFired)
        }

        Log.d(TAG, "Check: ${reading.percent}% charging=${reading.isCharging} enabled=$enabled -> $decision")

        if (decision is BatteryAlarmDecider.Decision.Trigger) {
            startAlarm(context, decision)
        }

        if (enabled) {
            schedule(context, nextDelayMs(reading.percent, reading.isCharging, config))
        }
    }

    fun schedule(context: Context, delayMs: Long) {
        setAlarm(context, System.currentTimeMillis() + delayMs, checkPendingIntent(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(checkPendingIntent(context))
    }

    // ── Internals ──

    private fun setAlarm(context: Context, triggerAt: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun startAlarm(context: Context, decision: BatteryAlarmDecider.Decision.Trigger) {
        val intent = Intent(context, BatteryAlarmService::class.java).apply {
            putExtra(BatteryAlarmService.EXTRA_BATTERY_PCT, decision.batteryPct)
            putExtra(BatteryAlarmService.EXTRA_DURATION_MS, decision.durationMs)
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

    private fun autoResumePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE_AUTO_RESUME,
            Intent(context, AutoResumeReceiver::class.java).setAction(ACTION_AUTO_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nextDelayMs(batteryPct: Int, isCharging: Boolean, config: ThresholdConfig): Long = when {
        isCharging -> INTERVAL_RELAXED_MS
        batteryPct > config.high + MARGIN_RELAXED -> INTERVAL_RELAXED_MS
        batteryPct > config.high + MARGIN_WATCHFUL -> INTERVAL_WATCHFUL_MS
        batteryPct > config.high -> INTERVAL_NEAR_THRESHOLD_MS
        else -> INTERVAL_CRITICAL_MS
    }
}
