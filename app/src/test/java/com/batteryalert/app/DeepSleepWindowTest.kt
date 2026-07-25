package com.batteryalert.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSleepWindowTest {

    private fun minutes(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `disabled window never matches`() {
        val window = DeepSleepWindow(enabled = false, startMinutes = minutes(22), endMinutes = minutes(7))
        assertFalse(window.contains(minutes(23)))
        assertFalse(window.contains(minutes(3)))
    }

    @Test
    fun `same-day window matches inside, not outside`() {
        val window = DeepSleepWindow(enabled = true, startMinutes = minutes(13), endMinutes = minutes(15))
        assertTrue(window.contains(minutes(13)))       // start inclusive
        assertTrue(window.contains(minutes(14, 30)))
        assertFalse(window.contains(minutes(15)))      // end exclusive
        assertFalse(window.contains(minutes(12, 59)))
        assertFalse(window.contains(minutes(20)))
    }

    @Test
    fun `midnight-crossing window matches late night and early morning`() {
        val window = DeepSleepWindow(enabled = true, startMinutes = minutes(22), endMinutes = minutes(7))
        assertTrue(window.contains(minutes(22)))       // start inclusive
        assertTrue(window.contains(minutes(23, 59)))
        assertTrue(window.contains(0))                 // midnight
        assertTrue(window.contains(minutes(6, 59)))
        assertFalse(window.contains(minutes(7)))       // end exclusive
        assertFalse(window.contains(minutes(12)))
        assertFalse(window.contains(minutes(21, 59)))
    }

    @Test
    fun `zero-length window never matches`() {
        val window = DeepSleepWindow(enabled = true, startMinutes = minutes(8), endMinutes = minutes(8))
        assertFalse(window.contains(minutes(8)))
        assertFalse(window.contains(minutes(9)))
    }

    @Test
    fun `default is disabled 22 to 07`() {
        val default = DeepSleepWindow.DEFAULT
        assertFalse(default.enabled)
        assertTrue(default.startMinutes == 22 * 60)
        assertTrue(default.endMinutes == 7 * 60)
    }
}
