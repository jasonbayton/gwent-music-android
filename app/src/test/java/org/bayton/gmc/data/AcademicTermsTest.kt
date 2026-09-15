package org.bayton.gmc.data

import org.bayton.gmc.data.model.DiaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AcademicTermsTest {

    @Test fun `autumn beginning in August uses the midpoint academic year`() {
        assertEquals("Autumn term · 2026/27",
            AcademicTerms.label(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 11, 19), hasTerm = true))
    }

    @Test fun `spring beginning in December uses the same academic year`() {
        assertEquals("Spring term · 2026/27",
            AcademicTerms.label(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 3, 20), hasTerm = true))
    }

    private fun de(date: String?, ay: String?, term: String?, subject: String = "Brass") =
        DiaryEntry(startDateTime = date, academicYear = ay, academicYearTerm = term, subject = subject)

    // ---- label ----

    @Test fun `autumn term labelled by season and academic year`() {
        assertEquals("Autumn term · 2026/27",
            AcademicTerms.label(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 12, 10), hasTerm = true))
    }

    @Test fun `spring term rolls the academic year back to the previous September`() {
        assertEquals("Spring term · 2025/26",
            AcademicTerms.label(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 3, 20), hasTerm = true))
    }

    @Test fun `a term starting in April is Summer, not Spring`() {
        assertEquals("Summer term · 2025/26",
            AcademicTerms.label(LocalDate.of(2026, 4, 24), LocalDate.of(2026, 7, 7), hasTerm = true))
    }

    @Test fun `a term-less block over the summer is the summer holiday`() {
        assertEquals("Summer holiday",
            AcademicTerms.label(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 27), hasTerm = false))
    }

    @Test fun `a term-less block outside summer is ad-hoc`() {
        assertEquals("Ad-hoc lessons",
            AcademicTerms.label(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 15), hasTerm = false))
    }

    // ---- group ----

    @Test fun `group splits by term and holiday, counts and orders correctly`() {
        val entries = listOf(
            de("2026-09-10T08:00:00", "ay27", "autumn"),
            de("2026-09-17T08:00:00", "ay27", "autumn"),
            de("2026-08-06T08:00:00", "ay26", null),
            de("2026-08-13T08:00:00", "ay26", null),
            de("2026-08-20T08:00:00", "ay26", null),
        )
        val groups = AcademicTerms.group(entries)
        assertEquals(2, groups.size)
        // earliest-first: the August holiday group precedes the September term.
        assertEquals("Summer holiday", groups[0].label)
        assertEquals(3, groups[0].lessons.size)
        assertEquals("Autumn term · 2026/27", groups[1].label)
        assertEquals(2, groups[1].lessons.size)
    }

    // ---- currentTerm ----

    @Test fun `between terms, the current term is the one the next lesson belongs to`() {
        // Today sits after the summer holiday block and before the autumn term (the real 6 Sep case).
        val now = LocalDateTime.of(2026, 9, 6, 12, 0)
        val entries = listOf(
            de("2026-08-27T08:00:00", "ay26", null),      // last holiday lesson (past)
            de("2026-09-10T08:00:00", "ay27", "autumn"),  // next lesson (future)
            de("2026-09-17T08:00:00", "ay27", "autumn"),
        )
        assertEquals(TermKey("ay27", "autumn"), AcademicTerms.currentTerm(entries, now))
    }

    @Test fun `with nothing upcoming, the current term is the most recent past one`() {
        val now = LocalDateTime.of(2026, 12, 31, 12, 0)
        val entries = listOf(
            de("2026-09-10T08:00:00", "ay27", "autumn"),
            de("2026-12-10T09:00:00", "ay27", "autumn"),
        )
        assertEquals(TermKey("ay27", "autumn"), AcademicTerms.currentTerm(entries, now))
    }

    @Test fun `no dated lessons yields no current term`() {
        assertNull(AcademicTerms.currentTerm(emptyList(), LocalDateTime.of(2026, 9, 6, 12, 0)))
    }

    @Test fun `lessons with missing or unparseable dates are ignored`() {
        val entries = listOf(
            de("2026-09-10T08:00:00", "ay27", "autumn"),
            de(null, "ay27", "autumn"),
            de("not-a-date", "ay27", "autumn"),
        )
        assertEquals(1, AcademicTerms.group(entries).single().lessons.size)
        assertEquals(TermKey("ay27", "autumn"),
            AcademicTerms.currentTerm(entries, LocalDateTime.of(2026, 9, 1, 0, 0)))
    }

    @Test fun `a term crossing the calendar year is labelled from its span and year`() {
        // A term with lessons in December and the following January stays Autumn 2026/27.
        assertEquals("Autumn term · 2026/27",
            AcademicTerms.label(LocalDate.of(2026, 12, 3), LocalDate.of(2027, 1, 14), hasTerm = true))
    }

    @Test fun `group labels a term from its whole span, not a subset`() {
        // A term running March to July is Summer even though its earliest lessons are in March.
        val g = AcademicTerms.group(listOf(
            de("2026-03-20T09:00:00", "ay26", "summer"),
            de("2026-07-10T09:00:00", "ay26", "summer"),
        )).single()
        assertEquals("Summer term · 2025/26", g.label)
    }
}
