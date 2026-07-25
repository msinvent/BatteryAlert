package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryAlertBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED only: it's a protected broadcast, so the sender is
        // guaranteed to be the system.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!Prefs.get(context).getBoolean(Prefs.KEY_ENABLED, true)) return

        Log.d(TAG, "Boot complete — running battery check")
        BatteryCheck.runNow(context)
    }
}
