package org.bayton.gmc.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.bayton.gmc.data.DiaryText
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * Fires at a planned time to post one reminder (MORNING today, or DIGEST for tomorrow), then
 * re-plans the next occurrence. The lessons are recomputed at fire time so a diary change since
 * planning is respected.
 * The default clock serves production; tests inject a fixed clock. Keep the generated
 * two-argument constructor so WorkManager can instantiate this worker by reflection.
 */
class ReminderWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): GwentRepository
        fun session(): Session
    }

    override suspend fun doWork(): Result = NotificationWorkLock.mutex.withLock { remind() }

    private suspend fun remind(): Result {
        val type = inputData.getString("type") ?: return Result.success()
        if (type != "MORNING" && type != "DIGEST") return Result.success()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val session = deps.session()
        val account = session.snapshot() ?: return Result.success()
        if (inputData.getString("owner") != account.owner) return Result.success()
        val prefs = NotificationPrefs(applicationContext)
        val now = LocalDateTime.now(clock)
        val nowMin = now.hour * 60 + now.minute

        val diary = try { deps.repo().schedule() } catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
        return session.ifCurrent(account) {
            val snapshots = ScheduleSnapshotStore(applicationContext)
            snapshots.observeDiary(diary, now, prefs.scheduleChanges)
            val unconfirmedDays = snapshots.unconfirmedDays()
            val all = diary.mapNotNull { d ->
                val dt = d.startDateTime?.take(19)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                PlannerLesson(dt, d.firstName?.trim().orEmpty(), DiaryText.subjectAndTeacher(d.subject).first, d.location, d.virtual)
            }

            // Old queued work has no day; do not reinterpret it as an occurrence for today.
            val targetDay = inputData.getString("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val expectedDay = if (type == "DIGEST") now.toLocalDate().plusDays(1) else now.toLocalDate()
            if (targetDay == null || targetDay != expectedDay) {
                NotificationScheduler.planReminders(applicationContext, all.filter { it.start.isAfter(now) }, prefs, now, unconfirmedDays)
                return@ifCurrent Result.success()
            }
            val dayIso = targetDay.toString()
            // An intermittent empty/partial observation must not consume this occurrence. Retry until
            // the target day is present again or its absence has been independently confirmed.
            if (targetDay in unconfirmedDays) return@ifCurrent Result.retry()
            val dayLessons = all.filter { it.start.toLocalDate() == targetDay && (it.child.isBlank() || prefs.pupilEnabled(it.child)) }

            // Post at most once per occurrence: a re-plan can enqueue a second near-immediate worker.
            if (dayLessons.isNotEmpty() && !prefs.inQuietHours(nowMin) && !prefs.reminderFired(type, dayIso)) {
                when (type) {
                    "MORNING" -> prefs.morning.also { if (it) Notifier.notifyMorning(applicationContext, "Don't forget your instrument", ReminderPlanner.morningText(dayLessons)) }
                    "DIGEST" -> prefs.dayBefore.also { if (it) Notifier.notifyDigest(applicationContext, "Tomorrow's lessons", ReminderPlanner.digestText(dayLessons)) }
                }
            }

            // This occurrence has been handled, including intentional suppression by preferences or
            // quiet hours. Persist before replanning so the grace window cannot enqueue it again.
            prefs.markReminderFired(type, dayIso)

            // Re-plan the next occurrence from upcoming lessons.
            val upcoming = all.filter { it.start.isAfter(now) }
            NotificationScheduler.planReminders(applicationContext, upcoming, prefs, now, unconfirmedDays)
            Result.success()
        } ?: Result.success()
    }
}
