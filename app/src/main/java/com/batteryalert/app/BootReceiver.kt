package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryAlertBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED only: it's a protected broadcast, so the sender is
        // guaranteed to be the system. QUICKBOOT_POWERON was spoofable by any
        // app holding RECEIVE_BOOT_COMPLETED (a normal permission).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_ENABLED, true)) return

        Log.d(TAG, "Boot complete — starting BatteryMonitorService")
        val serviceIntent = Intent(context, BatteryMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // FGS start can be disallowed (e.g. background-start restrictions);
            // never let that crash the receiver.
            Log.e(TAG, "Could not start service on boot: ${e.message}")
        }
    }
}
