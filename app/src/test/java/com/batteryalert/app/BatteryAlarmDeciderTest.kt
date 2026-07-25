package com.batteryalert.app

import com.batteryalert.app.BatteryAlarmDecider.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulates a phone charging and discharging by feeding a sequence of battery
 * levels into the pure decision layer, and asserts which alarms fire.
 */
class BatteryAlarmDeciderTest {

    private val decider = BatteryAlarmDecider()

    /** Feed one discharging tick (not charging, monitoring enabled, no alarm playing). */
    private fun discharge(pct: Int, isAlerting: Boolean = false): Decision =
        decider.onBatteryEvent(pct, isCharging = false, enabled = true, isAlerting = isAlerting)

    /** Feed one charging tick. */
    private fun charge(pct: Int, isAlerting: Boolean = false): Decision =
        decider.onBatteryEvent(pct, isCharging = true, enabled = true, isAlerting = isAlerting)

    /** Discharge across a whole range, collecting every alarm that fires. */
    private fun dischargeFrom(start: Int, end: Int): List<Int> {
        val fired = mutableListOf<Int>()
        for (pct in start downTo end) {
            (discharge(pct) as? Decision.Trigger)?.let { fired.add(it.thresholdPercent) }
        }
        return fired
    }

    @Test
    fun `no alarm while battery stays above 20 percent`() {
        assertTrue(dischargeFrom(100, 21).isEmpty())
    }

    @Test
    fun `full slow discharge fires each threshold exactly once, in order`() {
        assertEquals(listOf(20, 15, 10), dischargeFrom(100, 1))
    }

    @Test
    fun `crossing 20 fires only the 20 percent alarm, not on every tick below`() {
        assertEquals(20, (discharge(20) as Decision.Trigger).thresholdPercent)
        // Every subsequent tick down to 16 must stay silent.
        for (pct in 19 downTo 16) assertTrue(discharge(pct) is Decision.None)
    }

    @Test
    fun `sudden drop past 20 fires the 20 percent alarm at the level actually seen`() {
        // The reported "false alarm at 16%": battery jumps 25 to 16 with no tick at 20.
        val decision = discharge(16) as Decision.Trigger
        assertEquals(20, decision.thresholdPercent)
        assertEquals(16, decision.batteryPct)
    }

    @Test
    fun `dropping straight into critical fires only the 10 percent alarm`() {
        val decision = discharge(8) as Decision.Trigger
        assertEquals(10, decision.thresholdPercent)
        // 20 and 15 are now considered handled — no back-fill alarms.
        assertTrue(discharge(7) is Decision.None)
    }

    @Test
    fun `alarm messages name the threshold and the live level`() {
        assertEquals("Battery below 20% (now 16%)", (discharge(16) as Decision.Trigger).message)
        assertEquals("Battery below 15% (now 15%)", (discharge(15) as Decision.Trigger).message)
        assertEquals("Battery below 10% (now 9%)", (discharge(9) as Decision.Trigger).message)
    }

    @Test
    fun `default siren durations are 30s then 60s then 60s`() {
        assertEquals(30_000L, (discharge(20) as Decision.Trigger).durationMs)
        assertEquals(60_000L, (discharge(15) as Decision.Trigger).durationMs)
        assertEquals(60_000L, (discharge(10) as Decision.Trigger).durationMs)
    }

    @Test
    fun `charging never triggers an alarm even below threshold`() {
        assertTrue(charge(15) is Decision.None)
        assertTrue(charge(9) is Decision.None)
    }

    @Test
    fun `plugging in while an alarm is sounding silences it`() {
        assertTrue(discharge(10) is Decision.Trigger)
        assertEquals(Decision.Silence, charge(10, isAlerting = true))
    }

    @Test
    fun `plugging in with no alarm playing does not emit Silence`() {
        assertTrue(charge(10, isAlerting = false) is Decision.None)
    }

    @Test
    fun `charging back above the reset margin re-arms the alarms`() {
        assertTrue(discharge(20) is Decision.Trigger)
        assertTrue(discharge(18) is Decision.None) // already fired

        for (pct in 21..30) charge(pct) // recover above 22 -> flags reset

        assertEquals(20, (discharge(20) as Decision.Trigger).thresholdPercent)
    }

    @Test
    fun `a brief charge that stays at or below the margin does not re-arm`() {
        assertTrue(discharge(20) is Decision.Trigger)
        charge(21) // still within the 2% margin (<= 22) -> no reset
        charge(22)
        assertTrue(discharge(20) is Decision.None)
    }

    @Test
    fun `disabled monitoring suppresses all alarms`() {
        val quiet = decider.onBatteryEvent(9, isCharging = false, enabled = false, isAlerting = false)
        assertTrue(quiet is Decision.None)
    }

    @Test
    fun `fired flags are exposed for persistence`() {
        discharge(14)
        assertTrue(decider.highFired)
        assertTrue(decider.midFired)
        assertFalse(decider.lowFired)
    }

    @Test
    fun `decider restored from persisted flags does not re-alarm`() {
        // Simulates a fresh process (scheduled check) after 20/15 already fired.
        val restored = BatteryAlarmDecider(highFired = true, midFired = true)
        assertTrue(restored.onBatteryEvent(13, isCharging = false, enabled = true, isAlerting = false) is Decision.None)
        val critical = restored.onBatteryEvent(9, isCharging = false, enabled = true, isAlerting = false)
        assertEquals(10, (critical as Decision.Trigger).thresholdPercent)
    }

    @Test
    fun `restored decider re-arms after charging above the margin`() {
        val restored = BatteryAlarmDecider(highFired = true, midFired = true, lowFired = true)
        restored.onBatteryEvent(30, isCharging = true, enabled = true, isAlerting = false)
        assertFalse(restored.highFired)
        assertFalse(restored.midFired)
        assertFalse(restored.lowFired)
    }

    @Test
    fun `full charge-discharge cycle fires once per cycle`() {
        // Cycle 1: discharge to critical.
        assertEquals(listOf(20, 15, 10), dischargeFrom(40, 8))
        // Plug in, alarm silences, charge to full.
        assertEquals(Decision.Silence, charge(8, isAlerting = true))
        for (pct in 9..100) charge(pct)
        // Cycle 2: same alarms fire again.
        assertEquals(listOf(20, 15, 10), dischargeFrom(40, 8))
    }

    // ── Custom threshold configs ──

    @Test
    fun `custom thresholds fire at custom levels with matching messages`() {
        val custom = BatteryAlarmDecider(ThresholdConfig(high = 40, mid = 30, low = 20))
        val first = custom.onBatteryEvent(35, isCharging = false, enabled = true, isAlerting = false)
        assertEquals("Battery below 40% (now 35%)", (first as Decision.Trigger).message)
        val second = custom.onBatteryEvent(28, isCharging = false, enabled = true, isAlerting = false)
        assertEquals(30, (second as Decision.Trigger).thresholdPercent)
        val third = custom.onBatteryEvent(12, isCharging = false, enabled = true, isAlerting = false)
        assertEquals(20, (third as Decision.Trigger).thresholdPercent)
    }

    @Test
    fun `custom siren lengths flow into the trigger duration`() {
        val custom = BatteryAlarmDecider(
            ThresholdConfig(high = 40, mid = 30, low = 20, highSirenSec = 15, midSirenSec = 45, lowSirenSec = 60)
        )
        assertEquals(15_000L, (custom.onBatteryEvent(38, false, true, false) as Decision.Trigger).durationMs)
        assertEquals(45_000L, (custom.onBatteryEvent(29, false, true, false) as Decision.Trigger).durationMs)
        assertEquals(60_000L, (custom.onBatteryEvent(18, false, true, false) as Decision.Trigger).durationMs)
    }

    @Test
    fun `reset margin follows the custom high threshold`() {
        val custom = BatteryAlarmDecider(ThresholdConfig(high = 40, mid = 30, low = 20))
        assertTrue(custom.onBatteryEvent(40, false, true, false) is Decision.Trigger)

        custom.onBatteryEvent(42, isCharging = true, enabled = true, isAlerting = false) // <= 42: no reset
        assertTrue(custom.onBatteryEvent(39, false, true, false) is Decision.None)

        custom.onBatteryEvent(43, isCharging = true, enabled = true, isAlerting = false) // > 42: re-arm
        assertTrue(custom.onBatteryEvent(40, false, true, false) is Decision.Trigger)
    }
}
