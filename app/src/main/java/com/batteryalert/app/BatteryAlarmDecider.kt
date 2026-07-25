package com.batteryalert.app

/**
 * Pure, framework-free decision layer for the battery alarm.
 *
 * Holds the "which thresholds have already fired" state and, given a battery
 * event, decides whether to trigger an alarm, silence a running one, or do
 * nothing. Kept free of any Android dependency so it can be unit-tested on the
 * JVM (see BatteryAlarmDeciderTest); BatteryCheck runs it from scheduled
 * checks (persisting the flags), and BatteryAlarmService owns all the actual
 * side effects (audio, vibration, notifications, DND, the ring duration).
 */
class BatteryAlarmDecider(
    private val config: ThresholdConfig = ThresholdConfig.DEFAULT,
    highFired: Boolean = false,
    midFired: Boolean = false,
    lowFired: Boolean = false
) {

    sealed class Decision {
        /** Sound the alarm: [thresholdPercent] tripped at the given live [batteryPct]. */
        data class Trigger(
            val thresholdPercent: Int,
            val batteryPct: Int,
            val durationMs: Long
        ) : Decision() {
            val message: String get() = "Battery below $thresholdPercent% (now $batteryPct%)"
        }
        /** Charger connected while an alarm is active — stop it immediately. */
        object Silence : Decision()
        object None : Decision()
    }

    // Exposed (read-only) so callers can persist them across process death —
    // the checker runs from short-lived broadcasts, not a long-lived service.
    var highFired = highFired
        private set
    var midFired = midFired
        private set
    var lowFired = lowFired
        private set

    /**
     * @param isAlerting whether an alarm is currently playing — the caller
     *   owns the playback lifecycle, so it passes this in.
     */
    fun onBatteryEvent(
        batteryPct: Int,
        isCharging: Boolean,
        enabled: Boolean,
        isAlerting: Boolean
    ): Decision {
        // Only re-arm thresholds once safely above the top threshold, so a phone
        // hovering right at it doesn't chirp on every tiny charge/discharge wobble.
        if (isCharging && batteryPct > config.high + RESET_MARGIN) {
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
        batteryPct <= config.low && !lowFired -> {
            lowFired = true; midFired = true; highFired = true
            Decision.Trigger(config.low, batteryPct, config.lowSirenSec * MS_PER_SEC)
        }
        batteryPct <= config.mid && !midFired -> {
            midFired = true; highFired = true
            Decision.Trigger(config.mid, batteryPct, config.midSirenSec * MS_PER_SEC)
        }
        batteryPct <= config.high && !highFired -> {
            highFired = true
            Decision.Trigger(config.high, batteryPct, config.highSirenSec * MS_PER_SEC)
        }
        else -> Decision.None
    }

    private fun resetFlags() {
        highFired = false
        midFired = false
        lowFired = false
    }

    companion object {
        const val RESET_MARGIN = 2
        private const val MS_PER_SEC = 1_000L
    }
}
