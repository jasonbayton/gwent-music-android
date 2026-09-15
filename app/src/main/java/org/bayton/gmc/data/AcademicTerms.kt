package org.bayton.gmc.data

import org.bayton.gmc.data.model.DiaryEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Term grouping and labelling for the diary. A term's identity is (academicYear, academicYearTerm);
 * a null term id is ad-hoc / holiday tuition that sits outside the term structure (the vendor marks
 * summer-holiday blocks this way). The vendor gives term ids, not names, so labels are derived from
 * the lessons' dates - the same season logic the rest of the app uses.
 */
data class TermKey(val academicYear: String?, val term: String?)

data class TermGroup(
    val key: TermKey,
    val label: String,               // "Autumn term · 2026/27", "Summer holiday", ...
    val lessons: List<DiaryEntry>,   // ascending by start time
    val start: LocalDate,
    val end: LocalDate,
)

object AcademicTerms {

    fun parse(s: String?): LocalDateTime? =
        s?.take(19)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** Infer both season and academic year from the same representative date in the term. */
    fun label(start: LocalDate, end: LocalDate, hasTerm: Boolean): String {
        val middle = midpoint(start, end)
        val midMonth = middle.monthValue
        if (!hasTerm) return if (midMonth == 7 || midMonth == 8) "Summer holiday" else "Ad-hoc lessons"
        val season = when (midMonth) { in 9..12 -> "Autumn"; in 1..3 -> "Spring"; else -> "Summer" }
        // Using the start would put an August-start autumn term in the previous academic year.
        val ay = if (midMonth >= 9) middle.year else middle.year - 1
        val yy = ((ay + 1) % 100).toString().padStart(2, '0')
        return "$season term · $ay/$yy"
    }

    private fun midpoint(a: LocalDate, b: LocalDate): LocalDate =
        a.plusDays(ChronoUnit.DAYS.between(a, b) / 2)

    /** Group entries into terms, each labelled and date-sorted; groups returned earliest-first. */
    fun group(entries: List<DiaryEntry>): List<TermGroup> =
        entries.mapNotNull { e -> parse(e.startDateTime)?.let { it to e } }
            .groupBy { TermKey(it.second.academicYear, it.second.academicYearTerm) }
            .map { (key, pairs) ->
                val sorted = pairs.sortedBy { it.first }
                val start = sorted.first().first.toLocalDate()
                val end = sorted.last().first.toLocalDate()
                TermGroup(key, label(start, end, key.term != null), sorted.map { it.second }, start, end)
            }
            .sortedBy { it.start }

    /** Current term = the term of the next upcoming lesson; if none, of the most recent past lesson. */
    fun currentTerm(entries: List<DiaryEntry>, now: LocalDateTime): TermKey? {
        val dated = entries.mapNotNull { e -> parse(e.startDateTime)?.let { it to e } }
        val pick = dated.filter { !it.first.isBefore(now) }.minByOrNull { it.first }
            ?: dated.filter { it.first.isBefore(now) }.maxByOrNull { it.first }
            ?: return null
        return TermKey(pick.second.academicYear, pick.second.academicYearTerm)
    }
}
