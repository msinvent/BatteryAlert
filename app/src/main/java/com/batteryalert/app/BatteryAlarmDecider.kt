package com.batteryalert.app

/**
 * Pure, framework-free decision layer for the battery alarm.
 *
 * Holds the "which thresholds have already fired" state and, given a battery
 * event, decides whether to trigger an alarm, silence a running one, or do
 * nothing. Kept free of any Android dependency so it can be unit-tested on the
 * JVM (see BatteryAlarmDeciderTest); BatteryMonitorService owns all the actual
 * side effects (audio, vibration, notifications, DND, the auto-stop timer).
 */
class BatteryAlarmDecider {

    enum class Threshold(val percent: Int, val durationMs: Long) {
        LEVEL_20(20, 30_000L),
        LEVEL_15(15, 60_000L),
        LEVEL_10(10, 60_000L)
    }

    sealed class Decision {
        /** Sound the alarm for [threshold] at the given live [batteryPct]. */
        data class Trigger(val threshold: Threshold, val batteryPct: Int) : Decision() {
            val message: String get() = "Battery below ${threshold.percent}% (now $batteryPct%)"
        }
        /** Charger connected while an alarm is active — stop it immediately. */
        object Silence : Decision()
        object None : Decision()
    }

    private var alert20Fired = false
    private var alert15Fired = false
    private var alert10Fired = false

    /**
     * @param isAlerting whether the service currently has an alarm playing —
     *   the service owns the playback/timer lifecycle, so it passes this in.
     */
    fun onBatteryEvent(
        batteryPct: Int,
        isCharging: Boolean,
        enabled: Boolean,
        isAlerting: Boolean
    ): Decision {
        // Only re-arm thresholds once safely above the top threshold, so a phone
        // hovering right at 20% doesn't chirp on every tiny charge/discharge wobble.
        if (isCharging && batteryPct > Threshold.LEVEL_20.percent + RESET_MARGIN) {
            resetFlags()
        }
        if (isCharging && isAlerting) {
            return Decision.Silence
        }
        if (enabled && !isCharging) {
            return evaluate(batteryPct)
        }
        return Decision.None
    }

    private fun evaluate(batteryPct: Int): Decision = when {
        batteryPct <= Threshold.LEVEL_10.percent && !alert10Fired -> {
            alert10Fired = true; alert15Fired = true; alert20Fired = true
            Decision.Trigger(Threshold.LEVEL_10, batteryPct)
        }
        batteryPct <= Threshold.LEVEL_15.percent && !alert15Fired -> {
            alert15Fired = true; alert20Fired = true
            Decision.Trigger(Threshold.LEVEL_15, batteryPct)
        }
        batteryPct <= Threshold.LEVEL_20.percent && !alert20Fired -> {
            alert20Fired = true
            Decision.Trigger(Threshold.LEVEL_20, batteryPct)
        }
        else -> Decision.None
    }

    private fun resetFlags() {
        alert20Fired = false
        alert15Fired = false
        alert10Fired = false
    }

    companion object {
        private const val RESET_MARGIN = 2
    }
}
