package com.batteryalert.app

/**
 * A daily "no alarms" window, e.g. 22:00-07:00. Pure and framework-free so
 * the wrap-around-midnight logic is unit-testable; persistence lives in
 * BatteryCheck. Start is inclusive, end is exclusive. Thresholds crossed
 * during the window still fire on the first check after it ends.
 */
data class DeepSleepWindow(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int
) {

    fun isValid(): Boolean =
        startMinutes in 0 until MINUTES_PER_DAY && endMinutes in 0 until MINUTES_PER_DAY

    fun contains(minutesOfDay: Int): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) {
            minutesOfDay in startMinutes until endMinutes
        } else {
            // Crosses midnight: e.g. 22:00-07:00.
            minutesOfDay >= startMinutes || minutesOfDay < endMinutes
        }
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
        val DEFAULT = DeepSleepWindow(
            enabled = false,
            startMinutes = 22 * MINUTES_PER_HOUR,
            endMinutes = 7 * MINUTES_PER_HOUR
        )
    }
}
