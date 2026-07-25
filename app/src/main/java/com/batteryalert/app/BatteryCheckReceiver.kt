package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BatteryCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BatteryCheck.ACTION_BATTERY_CHECK) return
        BatteryCheck.runNow(context)
    }
}
