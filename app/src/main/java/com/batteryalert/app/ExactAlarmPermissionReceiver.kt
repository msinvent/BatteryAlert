package com.batteryalert.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-bootstraps the check chain when the user grants SCHEDULE_EXACT_ALARM —
 * revoking it cancels our scheduled checks, so without this the app would
 * stay silent until the next manual open.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        BatteryCheck.runNow(context)
    }
}
