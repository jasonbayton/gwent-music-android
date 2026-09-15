package org.bayton.gmc.notify

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A lesson reduced to what the reminders need. */
data class PlannerLesson(
    val start: LocalDateTime,
    val child: String,        // may be blank
    val subject: String,      // already cleaned
    val location: String?,
    val online: Boolean,
)

/** When a reminder should fire and which lessons it covers. */
data class Fire(val fireAt: LocalDateTime, val day: LocalDate, val lessons: List<PlannerLesson>)

/**
 * Chooses when the next morning reminder and next day-before digest should fire, purely from the
 * diary. The worker turns these into WorkManager jobs and notification text.
 */
object ReminderPlanner {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // A just-passed occurrence (up to this many minutes) is still selected, so a re-plan that lands
    // moments after the fire time targets the same occurrence (fired at most once via the idempotency
    // marker) rather than skipping to the next day and losing it.
    private const val GRACE_MINUTES = 5L

    /** Shared eligibility for planning and preserving an unconfirmed occurrence. */
    fun eligibleFireAt(now: LocalDateTime, day: LocalDate, minutes: Int, digest: Boolean, completed: Boolean): LocalDateTime? {
        val fireAt = (if (digest) day.minusDays(1) else day).atTime(LocalTime.of(minutes / 60, minutes % 60))
        val validDay = if (digest) day.isAfter(now.toLocalDate()) else !day.isBefore(now.toLocalDate())
        return fireAt.takeIf { validDay && it.isAfter(now.minusMinutes(GRACE_MINUTES)) && !completed }
    }

    /** Morning-of reminder: fires at `morningMinutes` on the next day that has an enabled lesson. */
    fun nextMorning(now: LocalDateTime, lessons: List<PlannerLesson>, morningMinutes: Int, pupilEnabled: (String) -> Boolean, completed: (LocalDate) -> Boolean = { false }): Fire? {
        val byDay = enabledByDay(lessons, pupilEnabled)
        return byDay.toSortedMap().entries.firstNotNullOfOrNull { (day, ls) ->
            eligibleFireAt(now, day, morningMinutes, false, completed(day))?.let { Fire(it, day, ls) }
        }
    }

    /** Day-before digest: fires at `digestMinutes` the evening BEFORE the next day that has lessons. */
    fun nextDigest(now: LocalDateTime, lessons: List<PlannerLesson>, digestMinutes: Int, pupilEnabled: (String) -> Boolean, completed: (LocalDate) -> Boolean = { false }): Fire? {
        val byDay = enabledByDay(lessons, pupilEnabled)
        return byDay.toSortedMap().entries.firstNotNullOfOrNull { (day, ls) ->
            // Grace must not cross midnight: the worker cannot deliver a day-before digest
            // on the lesson day, so selecting it again would create an immediate requeue loop.
            eligibleFireAt(now, day, digestMinutes, true, completed(day))?.let { Fire(it, day, ls) }
        }
    }

    private fun enabledByDay(lessons: List<PlannerLesson>, pupilEnabled: (String) -> Boolean): Map<LocalDate, List<PlannerLesson>> =
        lessons.filter { it.child.isBlank() || pupilEnabled(it.child) }
            .groupBy { it.start.toLocalDate() }
            .filterValues { it.isNotEmpty() }

    /** "Don't forget your instrument" body for a day's lessons. */
    fun morningText(lessons: List<PlannerLesson>): String = lessons.sortedBy { it.start }.joinToString("; ") { l ->
        val who = l.child.ifBlank { "Lesson" }
        "$who - ${l.subject} ${l.start.format(HHMM)}${l.location?.let { ", $it" }.orEmpty()}"
    }

    /** "Tomorrow's lessons" body for a day's lessons. */
    fun digestText(lessons: List<PlannerLesson>): String = lessons.sortedBy { it.start }.joinToString("\n") { l ->
        val who = l.child.ifBlank { "Lesson" }
        val online = if (l.online) " (online)" else ""
        "$who - ${l.subject} ${l.start.format(HHMM)}${l.location?.let { ", $it" }.orEmpty()}$online"
    }
}
