package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MainActivity.ACTION_AUTO_RESUME) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(MainActivity.KEY_ENABLED, true)
            .remove(MainActivity.KEY_RESUME_AT)
            .apply()

        BatteryCheck.runNow(context)
    }
}
