package com.batteryalert.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdConfigTest {

    @Test
    fun `default config is valid`() {
        assertTrue(ThresholdConfig.DEFAULT.isValid())
    }

    @Test
    fun `exact 5 percent gaps are valid`() {
        assertTrue(ThresholdConfig(high = 30, mid = 25, low = 20).isValid())
    }

    @Test
    fun `gap under 5 percent is rejected`() {
        assertFalse(ThresholdConfig(high = 20, mid = 16, low = 10).isValid())
        assertFalse(ThresholdConfig(high = 20, mid = 15, low = 11).isValid())
    }

    @Test
    fun `equal or ascending thresholds are rejected`() {
        assertFalse(ThresholdConfig(high = 15, mid = 15, low = 10).isValid())
        assertFalse(ThresholdConfig(high = 10, mid = 15, low = 20).isValid())
    }

    @Test
    fun `first alert must be strictly above second and second above third`() {
        // Implied by the gap rule, pinned here as an explicit requirement.
        assertTrue(ThresholdConfig(high = 30, mid = 25, low = 20).isValid())
        assertFalse(ThresholdConfig(high = 25, mid = 25, low = 20).isValid()) // first == second
        assertFalse(ThresholdConfig(high = 25, mid = 30, low = 20).isValid()) // second above first
        assertFalse(ThresholdConfig(high = 30, mid = 20, low = 25).isValid()) // third above second
        assertFalse(ThresholdConfig(high = 20, mid = 25, low = 30).isValid()) // fully ascending
    }

    @Test
    fun `bounds are enforced`() {
        assertFalse(ThresholdConfig(high = 96, mid = 50, low = 10).isValid())
        assertFalse(ThresholdConfig(high = 14, mid = 9, low = 4).isValid())
        assertTrue(ThresholdConfig(high = 95, mid = 50, low = 5).isValid())
    }

    @Test
    fun `siren lengths outside the allowed choices are rejected`() {
        assertFalse(ThresholdConfig(high = 20, mid = 15, low = 10, highSirenSec = 20).isValid())
        assertFalse(ThresholdConfig(high = 20, mid = 15, low = 10, midSirenSec = 0).isValid())
        assertFalse(ThresholdConfig(high = 20, mid = 15, low = 10, lowSirenSec = 120).isValid())
    }

    @Test
    fun `all allowed siren lengths are accepted`() {
        for (sec in ThresholdConfig.SIREN_CHOICES_SEC) {
            assertTrue(
                ThresholdConfig(high = 20, mid = 15, low = 10,
                    highSirenSec = sec, midSirenSec = sec, lowSirenSec = sec).isValid()
            )
        }
    }

    @Test
    fun `siren choices cycle in order and wrap around`() {
        assertTrue(ThresholdConfig.nextSirenChoice(15) == 30)
        assertTrue(ThresholdConfig.nextSirenChoice(30) == 45)
        assertTrue(ThresholdConfig.nextSirenChoice(45) == 60)
        assertTrue(ThresholdConfig.nextSirenChoice(60) == 15)
    }
}
