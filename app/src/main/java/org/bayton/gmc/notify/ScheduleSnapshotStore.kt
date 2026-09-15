package org.bayton.gmc.notify

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.bayton.gmc.data.DiaryText
import org.bayton.gmc.data.model.DiaryEntry
import kotlinx.coroutines.sync.Mutex

/** Both worker types share snapshot and reminder state, so serialise their observation/commit. */
internal object NotificationWorkLock { val mutex = Mutex() }

/** One upcoming lesson, as tracked for change detection. */
data class LessonSnap(val id: String, val startIso: String, val room: String, val child: String, val subject: String)

/**
 * Remembers the last-seen upcoming lessons and reports moves and cancellations on the next sync.
 * Additions are intentionally not alerted (a newly booked lesson is not a change to worry about).
 */
class ScheduleSnapshotStore(context: Context) {
    private val p = context.getSharedPreferences("gmc_schedule_snap", Context.MODE_PRIVATE)
    val isInitialised: Boolean get() = p.getBoolean("init", false)

    val hasPendingAbsences: Boolean get() = !p.getStringSet("missing", emptySet()).isNullOrEmpty()
    fun unconfirmedDays(): Set<java.time.LocalDate> {
        val ids = p.getStringSet("missing", emptySet()).orEmpty().map { it.substringBefore(DELIM) }.toSet()
        return read().values.filter { it.id in ids }.mapNotNull { parse(it.startIso)?.toLocalDate() }.toSet()
    }
    val hasPendingAlert: Boolean get() = p.getBoolean("pending_alert", false)
    fun alertDelivered() = p.edit().putBoolean("pending_alert", false).commit()

    fun observeDiary(diary: List<DiaryEntry>, now: LocalDateTime, alertsEnabled: Boolean): List<String> {
        fun clean(value: String?) = value.orEmpty().replace('\t', ' ').replace('\n', ' ').trim()
        val snaps = diary.mapNotNull { lesson ->
            val start = parse(lesson.startDateTime)?.takeIf { it.isAfter(now) } ?: return@mapNotNull null
            val id = lesson.diaryID?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LessonSnap(id, start.toString(), clean(lesson.subLocation ?: lesson.venueRoom),
                clean(lesson.firstName), clean(DiaryText.subjectAndTeacher(lesson.subject).first))
        }
        return detectAndCommit(snaps, now, alertsEnabled)
    }

    /** Require two valid observations at least 15 minutes apart before declaring a removal.
     * Retain unconfirmed rows and their existing reminder jobs meanwhile. */
    fun detectAndCommit(current: List<LessonSnap>, now: LocalDateTime, alertsEnabled: Boolean = true): List<String> {
        val old = read()
        val previousMissing = p.getStringSet("missing", emptySet()).orEmpty().mapNotNull {
            val parts = it.split(DELIM)
            if (parts.size == 2) parse(parts[1])?.let { time -> parts[0] to time } else null
        }.toMap()
        val currentIds = current.map { it.id }.toSet()
        val missing = old.values.filter { it.id !in currentIds && parse(it.startIso)?.isAfter(now) == true }
        val unconfirmed = missing.filter { previousMissing[it.id]?.plusMinutes(15)?.isAfter(now) != false }
        val effective = current + unconfirmed
        val messages = if (isInitialised) diff(old, effective, now) else emptyList()
        val missingRows = unconfirmed.map { it.id + DELIM + (previousMissing[it.id] ?: now).toString() }.toSet()
        write(effective, missingRows, alertsEnabled && (hasPendingAlert || messages.isNotEmpty()))
        return messages
    }

    fun clear() = p.edit().clear().commit()

    private fun read(): Map<String, LessonSnap> = (p.getStringSet(KEY, emptySet()) ?: emptySet())
        .mapNotNull { line -> line.split(DELIM).takeIf { it.size == 5 }?.let { LessonSnap(it[0], it[1], it[2], it[3], it[4]) } }
        .associateBy { it.id }

    private fun write(list: List<LessonSnap>, missing: Set<String>, pendingAlert: Boolean) {
        val set = list.map { listOf(it.id, it.startIso, it.room, it.child, it.subject).joinToString(DELIM) }.toSet()
        p.edit().putStringSet(KEY, set).putStringSet("missing", missing)
            .putBoolean("pending_alert", pendingAlert).putBoolean("init", true).commit()
    }

    companion object {
        const val KEY = "snap"
        const val DELIM = "\t"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

        private fun who(s: LessonSnap): String =
            if (s.child.isBlank()) s.subject else "${s.child}'s ${s.subject}"

        private fun parse(s: String?): LocalDateTime? = s?.take(19)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        /** Pure diff of a stored snapshot against the current lessons; a cancellation is only reported
         *  for a lesson that had not simply elapsed. Additions are not reported. */
        fun diff(old: Map<String, LessonSnap>, current: List<LessonSnap>, now: LocalDateTime): List<String> {
            val newById = current.associateBy { it.id }
            val messages = mutableListOf<String>()
            for ((id, o) in old) {
                val n = newById[id]
                val oStart = parse(o.startIso)
                when {
                    n == null && oStart != null && oStart.isAfter(now) ->
                        messages += "${who(o)} on ${oStart.format(DAY)} has been cancelled"
                    n != null && n.startIso != o.startIso -> parse(n.startIso)?.let {
                        messages += "${who(o)} moved to ${it.format(DAY_TIME)}"
                    }
                    n != null && n.room != o.room && n.room.isNotBlank() ->
                        messages += "${who(o)} is now in ${n.room}"
                }
            }
            return messages
        }
    }
}
