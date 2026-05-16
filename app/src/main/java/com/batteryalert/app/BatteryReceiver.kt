package com.batteryalert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Manifest-registered stub for legacy Android versions.
// On Android 8+, battery events are handled by BatteryMonitorService's internal receiver.
class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
