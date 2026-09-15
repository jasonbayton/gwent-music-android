package org.bayton.gmc.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    private val nineAm = 9 * 60
    private val fivePm = 17 * 60
    private val ninePm = 21 * 60
    private val sevenAm = 7 * 60

    @Test fun `daytime window includes its range and excludes the ends correctly`() {
        assertTrue(QuietHours.contains(12 * 60, nineAm, fivePm))
        assertTrue(QuietHours.contains(nineAm, nineAm, fivePm))     // start inclusive
        assertFalse(QuietHours.contains(fivePm, nineAm, fivePm))    // end exclusive
        assertFalse(QuietHours.contains(8 * 60, nineAm, fivePm))
    }

    @Test fun `overnight window wraps past midnight`() {
        assertTrue(QuietHours.contains(23 * 60, ninePm, sevenAm))   // 23:00
        assertTrue(QuietHours.contains(2 * 60, ninePm, sevenAm))    // 02:00
        assertTrue(QuietHours.contains(ninePm, ninePm, sevenAm))    // 21:00 start inclusive
        assertFalse(QuietHours.contains(sevenAm, ninePm, sevenAm))  // 07:00 end exclusive
        assertFalse(QuietHours.contains(12 * 60, ninePm, sevenAm))  // midday is not quiet
    }

    @Test fun `an empty window is never quiet`() {
        assertFalse(QuietHours.contains(ninePm, ninePm, ninePm))
    }
}
