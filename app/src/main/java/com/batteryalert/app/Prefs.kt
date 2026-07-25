package com.batteryalert.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Single access point for the app's SharedPreferences file and the keys
 * shared across components — so infrastructure never has to reach into
 * MainActivity for storage constants. BatteryCheck keeps its own private
 * keys (thresholds, fired flags, deep sleep) as an implementation detail.
 */
object Prefs {
    private const val NAME = "BatteryAlertPrefs"

    const val KEY_ENABLED = "alerts_enabled"
    const val KEY_RESUME_AT = "resume_at"
    const val KEY_ALARM_PROMPTED = "exact_alarm_prompted"
    const val KEY_THEME = "app_theme"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
