package com.batteryalert.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Short-lived siren: started by BatteryCheck when a threshold trips, rings for
 * the threshold's duration (or until the charger is plugged in), then stops
 * itself. Runs as a shortService FGS — no Play declaration needed, unlike the
 * old persistent specialUse monitor.
 */
class BatteryAlarmService : Service() {

    companion object {
        private const val TAG = "BatteryAlertAlarm"
        private const val CHANNEL_ID_ALERT = "battery_alert_critical"
        private const val NOTIFICATION_ID_ALERT = 2
        private const val VOLUME_UNSAVED = -1
        private const val WAKELOCK_EXTRA_MS = 5_000L
        private const val DEFAULT_DURATION_MS = 30_000L
        private const val LONG_RING_THRESHOLD_MS = 60_000L

        const val EXTRA_BATTERY_PCT = "battery_pct"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_MESSAGE = "message"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private lateinit var notificationManager: NotificationManager
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var chargingReceiver: BroadcastReceiver? = null

    private var previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    private var previousAlarmVolume = VOLUME_UNSAVED

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        createAlertChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batteryPct = intent?.getIntExtra(EXTRA_BATTERY_PCT, 0) ?: 0
        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.app_name)

        startForeground(
            NOTIFICATION_ID_ALERT,
            buildAlertNotification(message, durationMs),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        )

        Log.d(TAG, "Alarm: $message ($batteryPct%) for ${durationMs}ms")

        acquireWakeLock(durationMs + WAKELOCK_EXTRA_MS)
        bypassDoNotDisturb()
        playLoudSiren()
        startVibration()
        registerChargingReceiver()

        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = Runnable { stopSelf() }.also { handler.postDelayed(it, durationMs) }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // shortService timeout — the system demands we stop; all cleanup is in onDestroy.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopMediaPlayer()
        restoreAlarmVolume()
        try { vibrator?.cancel() } catch (_: Exception) {}
        restoreDoNotDisturb()
        chargingReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        releaseWakeLock()
        Log.d(TAG, "Alarm stopped")
    }

    private fun registerChargingReceiver() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL
                if (isCharging) {
                    Log.d(TAG, "Charging detected — silencing alarm")
                    stopSelf()
                }
            }
        }
        registerReceiver(
            chargingReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun playLoudSiren() {
        try {
            stopMediaPlayer()

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                if (previousAlarmVolume == VOLUME_UNSAVED) {
                    previousAlarmVolume = it.getStreamVolume(AudioManager.STREAM_ALARM)
                }
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

    private fun startVibration() {
        val vib = vibrator ?: return
        try {
            val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 200)
            vib.vibrate(VibrationEffect.createWaveform(pattern, intArrayOf(0, 255, 0, 255, 0, 255, 0), 0))
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
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

    private fun restoreAlarmVolume() {
        if (previousAlarmVolume == VOLUME_UNSAVED) return
        try {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Volume restore error: ${e.message}")
        }
        previousAlarmVolume = VOLUME_UNSAVED
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
                previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "DND restore error: ${e.message}")
        }
    }

    private fun createAlertChannel() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT, "Battery Critical Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical battery level alerts"
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            if (alarmUri != null) {
                setSound(alarmUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
        }
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun buildAlertNotification(message: String, durationMs: Long): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val durationText = if (durationMs >= LONG_RING_THRESHOLD_MS) "1 minute" else "30 seconds"
        return NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
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
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryAlert::AlarmWakeLock")
            ?.apply { acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
