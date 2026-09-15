package org.bayton.gmc.notify

import android.content.Context
import androidx.work.*
import org.bayton.gmc.BuildConfig
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Translates notification preferences and diary plans into account-labelled WorkManager requests.
 *
 * Unique names bound the queue to one job per purpose, not one job per account. Replacing input is
 * therefore part of account switching, and workers still check ownership when they run. Requested
 * times are earliest opportunities subject to connectivity and OS scheduling, not exact alarms;
 * reminder eligibility and duplicate suppression belong to the planner and worker at execution.
 */
object NotificationScheduler {
    private const val PERIODIC = "gmc-sync"
    private const val ONESHOT = "gmc-sync-now"
    private const val MORNING_WORK = "gmc-reminder-morning"
    private const val DIGEST_WORK = "gmc-reminder-digest"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic sync. UPDATE retains timing while replacing legacy or previous-account input. */
    fun schedulePeriodic(context: Context) {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setInputData(workDataOf("owner" to NotificationAccountState(context).owner))
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
    }

    /** Enqueue a sync without an initial delay; connectivity and OS scheduling still apply. */
    fun syncNow(context: Context) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("owner" to NotificationAccountState(context).owner))
            .setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, work)
    }

    /** Debug helper: run a sync on launch to exercise the flow on-device. */
    fun runNow(context: Context) { if (BuildConfig.DEBUG) syncNow(context) }

    /** (Re)schedule the next morning reminder and day-before digest from the current diary + prefs. */
    fun planReminders(context: Context, lessons: List<PlannerLesson>, prefs: NotificationPrefs, now: LocalDateTime,
        unconfirmedDays: Set<java.time.LocalDate> = emptySet()) {
        val wm = WorkManager.getInstance(context)
        fun plan(name: String, type: String, enabled: Boolean, fire: Fire?) {
            // Keep a possibly missing earlier occurrence, but still schedule a newly added earlier
            // lesson. An unrelated absence next week must not suppress today's reminder.
            val minutes = if (type == "DIGEST") prefs.digestMinutes else prefs.morningMinutes
            if (enabled && unconfirmedDays.any {
                ReminderPlanner.eligibleFireAt(now, it, minutes, type == "DIGEST", prefs.reminderFired(type, it.toString())) != null &&
                    (fire == null || !it.isAfter(fire.day))
            }) return
            enqueueReminder(wm, name, type, fire, NotificationAccountState(context).owner)
        }
        plan(MORNING_WORK, "MORNING", prefs.morning, if (prefs.morning) ReminderPlanner.nextMorning(now, lessons, prefs.morningMinutes, prefs::pupilEnabled) { prefs.reminderFired("MORNING", it.toString()) } else null)
        plan(DIGEST_WORK, "DIGEST", prefs.dayBefore, if (prefs.dayBefore) ReminderPlanner.nextDigest(now, lessons, prefs.digestMinutes, prefs::pupilEnabled) { prefs.reminderFired("DIGEST", it.toString()) } else null)
    }

    private fun enqueueReminder(wm: WorkManager, name: String, type: String, fire: Fire?, owner: String?) {
        if (fire == null) { wm.cancelUniqueWork(name); return }
        // Convert the target civil time to a real delay in the device zone, so the wall-clock time is
        // preserved across a DST transition (a plain LocalDateTime duration would drift by an hour).
        val zone = java.time.ZoneId.systemDefault()
        val delay = Duration.between(java.time.ZonedDateTime.now(zone), fire.fireAt.atZone(zone)).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("type" to type, "day" to fire.day.toString(), "owner" to owner))
            .setConstraints(connected)
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    /** Cancel all message-check, sync and reminder work (used on disconnect). */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(PERIODIC, ONESHOT, MORNING_WORK, DIGEST_WORK).forEach { wm.cancelUniqueWork(it) }
    }
}
