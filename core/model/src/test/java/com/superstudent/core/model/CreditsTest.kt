package com.superstudent.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditsTest {

    @Test
    fun `summing deltas never leaks binary floating point into the row`() {
        // The exact delta sequence observed on device, whose unrounded running sum displayed as
        // 11.159999999999998 and 15.649999999999999 before rounding was applied at the write site.
        val deltas = listOf(3.05, 0.58, 1.94, 0.74, 1.85, 1.28, 1.72, 4.49, 2.33, 1.61, 1.45, 1.54)
        var rounded = 0.0
        for (delta in deltas) {
            rounded = Credits.round(rounded + delta)
        }
        assertEquals(22.58, rounded, 0.0)
        assertEquals("22.58", Credits.format(rounded))
    }

    @Test
    fun `rounding is idempotent on an already clean total`() {
        assertEquals(54.01, Credits.round(Credits.round(54.01)), 0.0)
    }

    @Test
    fun `formats hundredths without trailing noise`() {
        // Every value below was rendered verbatim on device during the FR-13 trace run.
        assertEquals("11.16", Credits.format(11.159999999999998))
        assertEquals("15.65", Credits.format(15.649999999999999))
        assertEquals("34.95", Credits.format(34.949999999999996))
        assertEquals("39.02", Credits.format(39.019999999999996))
        assertEquals("43.23", Credits.format(43.22999999999999))
        assertEquals("47.85", Credits.format(47.84999999999999))
        assertEquals("49.48", Credits.format(49.48))
        assertEquals("3.05", Credits.format(3.05))
    }

    @Test
    fun `formats whole credits without a decimal point`() {
        assertEquals("12", Credits.format(12.0))
        assertEquals("12", Credits.format(11.999999999999998))
    }
}
