package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BatteryCheck.ACTION_AUTO_RESUME) return
        BatteryCheck.resume(context)
    }
}
