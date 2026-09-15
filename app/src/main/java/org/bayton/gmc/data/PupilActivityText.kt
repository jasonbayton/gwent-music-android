package org.bayton.gmc.data

import java.time.LocalDate

/** Parsing for a pupil's tuition-application rows. */
object PupilActivityText {

    /** narrative is "Activity at Venue"; fall back to the venue-name field when it is not. */
    fun splitNarrative(narrative: String?, venueFallback: String?): Pair<String, String> {
        val n = narrative?.trim().orEmpty()
        val i = n.indexOf(" at ", ignoreCase = true)
        return if (i > 0) n.substring(0, i).trim() to n.substring(i + 4).trim()
        else n.ifBlank { "Tuition" } to venueFallback?.trim().orEmpty()
    }

    fun statusLabel(s: String?): String = when (s?.trim()?.uppercase()) {
        "A" -> "Accepted"; "O" -> "Offered"; "P" -> "Pending"; "W" -> "Waiting list"
        "I" -> "Invited"; "R" -> "Rejected"; "L" -> "Left"; "C" -> "Cancelled"
        null, "" -> ""; else -> s!!.trim()
    }

    /** Academic year runs Sept-Aug; a Sept-onward start belongs to that year, otherwise the previous.
     *  Returns 0 for a null or unparseable date. */
    fun academicYearOf(s: String?): Int {
        val d = s?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 0
        return if (d.monthValue >= 9) d.year else d.year - 1
    }
}
