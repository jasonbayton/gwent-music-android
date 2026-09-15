package org.bayton.gmc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PupilActivityTextTest {

    @Test fun `splitNarrative separates activity and venue on the first at`() {
        assertEquals("Brass" to "Example Secondary School", PupilActivityText.splitNarrative("Brass at Example Secondary School", null))
    }

    @Test fun `splitNarrative falls back to the venue name when there is no at`() {
        assertEquals("Brass" to "Example Studio", PupilActivityText.splitNarrative("Brass", "Example Studio"))
    }

    @Test fun `splitNarrative labels a blank narrative`() {
        assertEquals("Tuition" to "", PupilActivityText.splitNarrative("", null))
        assertEquals("Tuition" to "", PupilActivityText.splitNarrative(null, null))
    }

    @Test fun `statusLabel maps known codes and passes unknown through`() {
        assertEquals("Accepted", PupilActivityText.statusLabel("A"))
        assertEquals("Offered", PupilActivityText.statusLabel("o"))
        assertEquals("Left", PupilActivityText.statusLabel("L"))
        assertEquals("", PupilActivityText.statusLabel(null))
        assertEquals("", PupilActivityText.statusLabel(""))
        assertEquals("Z9", PupilActivityText.statusLabel("Z9"))
    }

    @Test fun `academicYearOf places a September-onward start in that year`() {
        assertEquals(2026, PupilActivityText.academicYearOf("2026-09-01T00:00:00"))
        assertEquals(2026, PupilActivityText.academicYearOf("2026-12-31"))
    }

    @Test fun `academicYearOf places a pre-September start in the previous year`() {
        assertEquals(2025, PupilActivityText.academicYearOf("2026-04-28T23:00:00")) // summer term -> 2025/26
        assertEquals(2025, PupilActivityText.academicYearOf("2026-08-31"))
    }

    @Test fun `academicYearOf returns zero for a missing or unparseable date`() {
        assertEquals(0, PupilActivityText.academicYearOf(null))
        assertEquals(0, PupilActivityText.academicYearOf("not-a-date"))
    }
}
