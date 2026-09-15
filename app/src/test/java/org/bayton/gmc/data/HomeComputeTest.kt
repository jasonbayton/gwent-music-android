package org.bayton.gmc.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HomeComputeTest {

    @Test fun `who names one, two, or many pupils`() {
        assertEquals("Your lessons", HomeCompute.who(emptyList()))
        assertEquals("Alex's lessons", HomeCompute.who(listOf("Alex")))
        assertEquals("Alex & Sam's lessons", HomeCompute.who(listOf("Alex", "Sam")))
        assertEquals("Your pupils' lessons", HomeCompute.who(listOf("Alex", "Sam", "Robin")))
    }

    @Test fun `eight lessons in one week count as a single dot`() {
        val mon = LocalDate.of(2026, 9, 7)
        val eightSameWeek = (0..7).map { mon.plusDays((it % 5).toLong()).atTime(8 + it % 3, 0) }
        assertEquals(1, HomeCompute.upcomingWeeks(eightSameWeek))
    }

    @Test fun `lessons spread across three weeks count as three dots`() {
        val mon = LocalDate.of(2026, 9, 7)
        val threeWeeks = listOf(mon.atTime(8, 0), mon.plusDays(8).atTime(9, 0), mon.plusDays(15).atTime(10, 0))
        assertEquals(3, HomeCompute.upcomingWeeks(threeWeeks))
    }

    @Test fun `a week spanning the new year is still one week`() {
        // Mon 29 Dec 2025 and Thu 1 Jan 2026 share the same ISO week.
        val starts = listOf(LocalDate.of(2025, 12, 29).atTime(8, 0), LocalDate.of(2026, 1, 1).atTime(8, 0))
        assertEquals(1, HomeCompute.upcomingWeeks(starts))
    }

    @Test fun `no lessons means no dots`() {
        assertEquals(0, HomeCompute.upcomingWeeks(emptyList()))
    }
}
