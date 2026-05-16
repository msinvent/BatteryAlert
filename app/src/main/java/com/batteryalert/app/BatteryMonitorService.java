package com.batteryalert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class BatteryMonitorService extends Service {

    private static final String TAG = "BatteryAlertService";
    private static final String CHANNEL_ID_SERVICE = "battery_monitor_service";
    private static final String CHANNEL_ID_ALERT = "battery_alert_critical";
    private static final int NOTIFICATION_ID_SERVICE = 1;
    private static final int NOTIFICATION_ID_ALERT = 2;

    // Alert thresholds
    private static final int THRESHOLD_20 = 20;
    private static final int THRESHOLD_15 = 15;
    private static final int THRESHOLD_10 = 10;

    // Durations in milliseconds
    private static final long DURATION_20 = 30_000L;   // 30 seconds
    private static final long DURATION_15 = 60_000L;   // 1 minute
    private static final long DURATION_10 = 60_000L;   // 1 minute

    private BroadcastReceiver batteryReceiver;
    private MediaPlayer mediaPlayer;
    private Handler handler;
    private Runnable stopAlarmRunnable;
    private NotificationManager notificationManager;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    // Track which alerts have already fired this charge cycle
    private boolean alert20Fired = false;
    private boolean alert15Fired = false;
    private boolean alert10Fired = false;
    private boolean isAlerting = false;

    // Track last known battery to detect charging (reset fired flags)
    private int lastBatteryLevel = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Setup vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        createNotificationChannels();
        acquireWakeLock();
        registerBatteryReceiver();

        Log.d(TAG, "BatteryMonitorService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification());
        return START_STICKY; // Restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarm();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        }
        releaseWakeLock();
        Log.d(TAG, "BatteryMonitorService destroyed");
    }

    // ─── Battery Monitoring ───────────────────────────────────────────────────

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    int batteryPct = (int) ((level / (float) scale) * 100);

                    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

                    // Reset alert flags when charging
                    if (isCharging && batteryPct > THRESHOLD_20 + 2) {
                        alert20Fired = false;
                        alert15Fired = false;
                        alert10Fired = false;
                        Log.d(TAG, "Charging detected — alert flags reset");
                    }

                    // Check preferences
                    SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
                    boolean alertsEnabled = prefs.getBoolean(MainActivity.KEY_ENABLED, true);

                    if (alertsEnabled && !isCharging) {
                        checkAndTriggerAlerts(batteryPct);
                    }

                    lastBatteryLevel = batteryPct;
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
    }

    private void checkAndTriggerAlerts(int batteryPct) {
        Log.d(TAG, "Battery: " + batteryPct + "% | 20fired=" + alert20Fired + " 15fired=" + alert15Fired + " 10fired=" + alert10Fired);

        if (batteryPct <= THRESHOLD_10 && !alert10Fired) {
            alert10Fired = true;
            alert15Fired = true;
            alert20Fired = true;
            triggerAlert(batteryPct, DURATION_10, "CRITICAL: " + batteryPct + "% Battery!");
        } else if (batteryPct <= THRESHOLD_15 && !alert15Fired) {
            alert15Fired = true;
            alert20Fired = true;
            triggerAlert(batteryPct, DURATION_15, "WARNING: " + batteryPct + "% Battery!");
        } else if (batteryPct <= THRESHOLD_20 && !alert20Fired) {
            alert20Fired = true;
            triggerAlert(batteryPct, DURATION_20, "ALERT: " + batteryPct + "% Battery!");
        }
    }

    // ─── Alert Triggering ─────────────────────────────────────────────────────

    private void triggerAlert(int batteryPct, long durationMs, String message) {
        if (isAlerting) {
            stopAlarm(); // Stop any ongoing alarm first
        }

        Log.d(TAG, "Triggering alert: " + message + " for " + durationMs + "ms");
        isAlerting = true;

        // Bypass DND
        bypassDoNotDisturb();

        // Play loud siren
        playLoudSiren();

        // Vibrate
        startVibration(durationMs);

        // Show full-screen notification
        showAlertNotification(batteryPct, message);

        // Schedule stop
        if (stopAlarmRunnable != null) {
            handler.removeCallbacks(stopAlarmRunnable);
        }
        stopAlarmRunnable = () -> {
            stopAlarm();
            restoreDoNotDisturb();
        };
        handler.postDelayed(stopAlarmRunnable, durationMs);
    }

    private void playLoudSiren() {
        try {
            stopMediaPlayer();

            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            // Force max alarm volume
            if (audioManager != null) {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0);
            }

            // Use alarm ringtone (bypasses DND on most devices)
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getApplicationContext(), alarmUri);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)          // ALARM usage bypasses DND
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // Force audibility
                    .build();

            mediaPlayer.setAudioAttributes(audioAttributes);
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();

            Log.d(TAG, "Siren started");
        } catch (Exception e) {
            Log.e(TAG, "Error playing siren: " + e.getMessage());
        }
    }

    private void startVibration(long durationMs) {
        if (vibrator == null) return;
        try {
            // Intense siren-like vibration pattern: short-short-long
            long[] pattern = {0, 200, 100, 200, 100, 500, 200};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int[] amplitudes = {0, 255, 0, 255, 0, 255, 0};
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, amplitudes, 0);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibration error: " + e.getMessage());
        }
    }

    private void stopAlarm() {
        isAlerting = false;
        stopMediaPlayer();
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
        }
        if (stopAlarmRunnable != null) {
            handler.removeCallbacks(stopAlarmRunnable);
            stopAlarmRunnable = null;
        }
        notificationManager.cancel(NOTIFICATION_ID_ALERT);
        Log.d(TAG, "Alarm stopped");
    }

    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    // ─── Do Not Disturb Bypass ────────────────────────────────────────────────

    private int previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;

    private void bypassDoNotDisturb() {
        try {
            if (notificationManager != null && notificationManager.isNotificationPolicyAccessGranted()) {
                previousInterruptionFilter = notificationManager.getCurrentInterruptionFilter();
                // Set to allow alarms (INTERRUPTION_FILTER_ALARMS allows alarm-priority notifications)
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                Log.d(TAG, "DND bypassed — set to ALARMS mode (previous: " + previousInterruptionFilter + ")");
            } else {
                Log.w(TAG, "No DND permission — alarm audio attributes will attempt bypass");
                // Even without permission, USAGE_ALARM + FLAG_AUDIBILITY_ENFORCED
                // will still play on most devices through DND
            }
        } catch (Exception e) {
            Log.e(TAG, "DND bypass error: " + e.getMessage());
        }
    }

    private void restoreDoNotDisturb() {
        try {
            if (notificationManager != null && notificationManager.isNotificationPolicyAccessGranted()
                    && previousInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                notificationManager.setInterruptionFilter(previousInterruptionFilter);
                Log.d(TAG, "DND restored to: " + previousInterruptionFilter);
            }
        } catch (Exception e) {
            Log.e(TAG, "DND restore error: " + e.getMessage());
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // Service channel (silent, persistent)
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    "Battery Monitor",
                    NotificationManager.IMPORTANCE_MIN);
            serviceChannel.setDescription("Keeps battery monitoring active in background");
            serviceChannel.setShowBadge(false);
            notificationManager.createNotificationChannel(serviceChannel);

            // Alert channel — IMPORTANCE_HIGH, bypasses DND via alarm
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    "Battery Critical Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Critical battery level siren alerts");
            alertChannel.enableVibration(true);
            alertChannel.setBypassDnd(true); // Bypass DND at channel level
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            // Use alarm sound for the notification channel too
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            if (alarmUri != null) alertChannel.setSound(alarmUri, attrs);

            notificationManager.createNotificationChannel(alertChannel);
        }
    }

    private Notification buildServiceNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setContentTitle("Battery Alert Active")
                .setContentText("Monitoring battery — will alert at 20%, 15%, 10%")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void showAlertNotification(int batteryPct, String message) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String durationText = batteryPct <= THRESHOLD_10 ? "1 minute"
                : batteryPct <= THRESHOLD_15 ? "1 minute" : "30 seconds";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setContentTitle("🔋 " + message)
                .setContentText("Alarm will sound for " + durationText + ". Charge your device NOW!")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true) // Show on lock screen
                .setAutoCancel(false)
                .setOngoing(true);

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build());
    }

    // ─── Wake Lock ────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BatteryAlert::MonitorWakeLock");
            wakeLock.acquire(10 * 60 * 1000L); // 10 min max
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
