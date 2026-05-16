package com.batteryalert.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BatteryMonitorService : Service() {

    companion object {
        private const val TAG = "BatteryAlertService"
        private const val CHANNEL_ID_SERVICE = "battery_monitor_service"
        private const val CHANNEL_ID_ALERT = "battery_alert_critical"
        private const val NOTIFICATION_ID_SERVICE = 1
        private const val NOTIFICATION_ID_ALERT = 2

        private const val THRESHOLD_20 = 20
        private const val THRESHOLD_15 = 15
        private const val THRESHOLD_10 = 10

        private const val DURATION_20 = 30_000L
        private const val DURATION_15 = 60_000L
        private const val DURATION_10 = 60_000L
    }

    private var batteryReceiver: BroadcastReceiver? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopAlarmRunnable: Runnable? = null
    private lateinit var notificationManager: NotificationManager
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var alert20Fired = false
    private var alert15Fired = false
    private var alert10Fired = false
    private var isAlerting = false

    private var previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        createNotificationChannels()
        acquireWakeLock()
        registerBatteryReceiver()

        Log.d(TAG, "BatteryMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        releaseWakeLock()
        Log.d(TAG, "BatteryMonitorService destroyed")
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val batteryPct = (level / scale.toFloat() * 100).toInt()

                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL

                if (isCharging && batteryPct > THRESHOLD_20 + 2) {
                    alert20Fired = false
                    alert15Fired = false
                    alert10Fired = false
                    Log.d(TAG, "Charging detected — alert flags reset")
                }

                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(MainActivity.KEY_ENABLED, true) && !isCharging) {
                    checkAndTriggerAlerts(batteryPct)
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun checkAndTriggerAlerts(batteryPct: Int) {
        Log.d(TAG, "Battery: $batteryPct% | 20fired=$alert20Fired 15fired=$alert15Fired 10fired=$alert10Fired")
        when {
            batteryPct <= THRESHOLD_10 && !alert10Fired -> {
                alert10Fired = true; alert15Fired = true; alert20Fired = true
                triggerAlert(batteryPct, DURATION_10, "CRITICAL: $batteryPct% Battery!")
            }
            batteryPct <= THRESHOLD_15 && !alert15Fired -> {
                alert15Fired = true; alert20Fired = true
                triggerAlert(batteryPct, DURATION_15, "WARNING: $batteryPct% Battery!")
            }
            batteryPct <= THRESHOLD_20 && !alert20Fired -> {
                alert20Fired = true
                triggerAlert(batteryPct, DURATION_20, "ALERT: $batteryPct% Battery!")
            }
        }
    }

    private fun triggerAlert(batteryPct: Int, durationMs: Long, message: String) {
        if (isAlerting) stopAlarm()

        Log.d(TAG, "Triggering alert: $message for ${durationMs}ms")
        isAlerting = true

        bypassDoNotDisturb()
        playLoudSiren()
        startVibration(durationMs)
        showAlertNotification(batteryPct, message)

        stopAlarmRunnable?.let { handler.removeCallbacks(it) }
        stopAlarmRunnable = Runnable {
            stopAlarm()
            restoreDoNotDisturb()
        }.also { handler.postDelayed(it, durationMs) }
    }

    private fun playLoudSiren() {
        try {
            stopMediaPlayer()

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                it.setStreamVolume(AudioManager.STREAM_ALARM, it.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Siren started")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren: ${e.message}")
        }
    }

    private fun startVibration(durationMs: Long) {
        val vib = vibrator ?: return
        try {
            val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, intArrayOf(0, 255, 0, 255, 0, 255, 0), 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
    }

    private fun stopAlarm() {
        isAlerting = false
        stopMediaPlayer()
        try { vibrator?.cancel() } catch (_: Exception) {}
        stopAlarmRunnable?.let {
            handler.removeCallbacks(it)
            stopAlarmRunnable = null
        }
        notificationManager.cancel(NOTIFICATION_ID_ALERT)
        Log.d(TAG, "Alarm stopped")
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    private fun bypassDoNotDisturb() {
        try {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                previousInterruptionFilter = notificationManager.currentInterruptionFilter
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                Log.d(TAG, "DND bypassed (previous: $previousInterruptionFilter)")
            } else {
                Log.w(TAG, "No DND permission — alarm audio attributes will attempt bypass")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DND bypass error: ${e.message}")
        }
    }

    private fun restoreDoNotDisturb() {
        try {
            if (notificationManager.isNotificationPolicyAccessGranted
                && previousInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                notificationManager.setInterruptionFilter(previousInterruptionFilter)
                Log.d(TAG, "DND restored to: $previousInterruptionFilter")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DND restore error: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE, "Battery Monitor", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps battery monitoring active in background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT, "Battery Critical Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical battery level alerts"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (alarmUri != null) {
                    setSound(alarmUri, AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                }
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("Battery Alert Active")
            .setContentText("Monitoring battery — will alert at 20%, 15%, 10%")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showAlertNotification(batteryPct: Int, message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val durationText = if (batteryPct <= THRESHOLD_15) "1 minute" else "30 seconds"
        notificationManager.notify(NOTIFICATION_ID_ALERT,
            NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setContentTitle("🔋 $message")
                .setContentText("Alarm will sound for $durationText. Charge your device NOW!")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryAlert::MonitorWakeLock")
            ?.apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
