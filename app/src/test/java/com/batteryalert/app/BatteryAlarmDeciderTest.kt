package com.batteryalert.app

import com.batteryalert.app.BatteryAlarmDecider.Decision
import com.batteryalert.app.BatteryAlarmDecider.Threshold
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
    private fun dischargeFrom(start: Int, end: Int): List<Threshold> {
        val fired = mutableListOf<Threshold>()
        for (pct in start downTo end) {
            (discharge(pct) as? Decision.Trigger)?.let { fired.add(it.threshold) }
        }
        return fired
    }

    @Test
    fun `no alarm while battery stays above 20 percent`() {
        assertTrue(dischargeFrom(100, 21).isEmpty())
    }

    @Test
    fun `full slow discharge fires each threshold exactly once, in order`() {
        assertEquals(
            listOf(Threshold.LEVEL_20, Threshold.LEVEL_15, Threshold.LEVEL_10),
            dischargeFrom(100, 1)
        )
    }

    @Test
    fun `crossing 20 fires only the 20 percent alarm, not on every tick below`() {
        assertEquals(Threshold.LEVEL_20, (discharge(20) as Decision.Trigger).threshold)
        // Every subsequent tick down to 16 must stay silent.
        for (pct in 19 downTo 16) assertTrue(discharge(pct) is Decision.None)
    }

    @Test
    fun `sudden drop past 20 fires the 20 percent alarm at the level actually seen`() {
        // The reported "false alarm at 16%": battery jumps 25 to 16 with no tick at 20.
        val decision = discharge(16) as Decision.Trigger
        assertEquals(Threshold.LEVEL_20, decision.threshold)
        assertEquals(16, decision.batteryPct)
    }

    @Test
    fun `dropping straight into critical fires only the 10 percent alarm`() {
        val decision = discharge(8) as Decision.Trigger
        assertEquals(Threshold.LEVEL_10, decision.threshold)
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

        assertEquals(Threshold.LEVEL_20, (discharge(20) as Decision.Trigger).threshold)
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
        assertTrue(decider.alert20Fired)
        assertTrue(decider.alert15Fired)
        assertFalse(decider.alert10Fired)
    }

    @Test
    fun `decider restored from persisted flags does not re-alarm`() {
        // Simulates a fresh process (scheduled check) after 20/15 already fired.
        val restored = BatteryAlarmDecider(alert20Fired = true, alert15Fired = true)
        assertTrue(restored.onBatteryEvent(13, isCharging = false, enabled = true, isAlerting = false) is Decision.None)
        val critical = restored.onBatteryEvent(9, isCharging = false, enabled = true, isAlerting = false)
        assertEquals(Threshold.LEVEL_10, (critical as Decision.Trigger).threshold)
    }

    @Test
    fun `restored decider re-arms after charging above the margin`() {
        val restored = BatteryAlarmDecider(alert20Fired = true, alert15Fired = true, alert10Fired = true)
        restored.onBatteryEvent(30, isCharging = true, enabled = true, isAlerting = false)
        assertFalse(restored.alert20Fired)
        assertFalse(restored.alert15Fired)
        assertFalse(restored.alert10Fired)
    }

    @Test
    fun `full charge-discharge cycle fires once per cycle`() {
        // Cycle 1: discharge to critical.
        assertEquals(
            listOf(Threshold.LEVEL_20, Threshold.LEVEL_15, Threshold.LEVEL_10),
            dischargeFrom(40, 8)
        )
        // Plug in, alarm silences, charge to full.
        assertEquals(Decision.Silence, charge(8, isAlerting = true))
        for (pct in 9..100) charge(pct)
        // Cycle 2: same alarms fire again.
        assertEquals(
            listOf(Threshold.LEVEL_20, Threshold.LEVEL_15, Threshold.LEVEL_10),
            dischargeFrom(40, 8)
        )
    }
}
