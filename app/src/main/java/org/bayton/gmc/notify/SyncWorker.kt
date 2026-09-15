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
import java.time.LocalDateTime
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * Periodic background sync while signed in:
 *  - new-message notifications (baseline on first run, then only genuinely new ones),
 *  - schedule-change alerts (moved/cancelled lessons versus the last sync),
 *  - (re)planning the morning reminder and day-before digest from the current diary.
 *
 * The worker mutex serialises observation and delivery against other notification workers. It does
 * not freeze the account: queued owner input rejects obsolete jobs, and Session's snapshot guard
 * rechecks ownership after each fetch before mutating stores or posting notifications. Stale work
 * completes successfully because retrying it cannot make it relevant to the new account.
 *
 * Fetch failures retry without treating missing data as a successful empty response. Delivery state
 * advances only when the notifier accepts an alert; quiet hours leave pending changes for later.
 */
class SyncWorker @JvmOverloads constructor(context: Context, params: WorkerParameters, private val clock: Clock = Clock.systemDefaultZone()) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): GwentRepository
        fun session(): Session
    }

    override suspend fun doWork(): Result = NotificationWorkLock.mutex.withLock { sync() }

    private suspend fun sync(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val session = deps.session()
        val account = session.snapshot() ?: return Result.success()
        if (inputData.getString("owner") != account.owner) return Result.success()
        val prefs = NotificationPrefs(applicationContext)
        val now = LocalDateTime.now(clock)
        val nowMin = now.hour * 60 + now.minute

        // 1) Messages.
        val messages = try { deps.repo().messages(sent = false) } catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
        session.ifCurrent(account) {
            val seen = SeenStore(applicationContext)
            // Only UNREAD messages can be "new". messages() returns the whole inbox (read history
            // included, and locally-read items marked read), so fingerprinting everything meant a read
            // backlog absent from the baseline - after a reconnect, an empty first sync, or a
            // fingerprint change - fired as dozens of bogus "new message" alerts. Read items, whether
            // read in-app or on the portal, must never notify.
            val currentFps = messages.filter { it.viewedDate.isNullOrBlank() }.map {
                SeenStore.fingerprint(it.subject, it.dateStamp, it.fromContactID, it.firstName, it.lastName)
            }.toSet()
            val pending = seen.observe(currentFps, prefs.messages)
            if (pending.isNotEmpty() && !prefs.inQuietHours(nowMin) && Notifier.notifyNewMessages(applicationContext, pending.size))
                seen.delivered(pending)
            Unit
        } ?: return Result.success()

        // 2) Schedule: fetch the diary once, use it for change detection and reminder planning.
        val diary = try { deps.repo().schedule() } catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
        return session.ifCurrent(account) {
            val upcoming = diary.mapNotNull { d ->
                val dt = d.startDateTime?.take(19)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (!dt.isAfter(now)) return@mapNotNull null
                val subject = DiaryText.subjectAndTeacher(d.subject).first
                Pair(dt, d) to PlannerLesson(dt, d.firstName?.trim().orEmpty(), subject, d.location, d.virtual)
            }

            val snapStore = ScheduleSnapshotStore(applicationContext)
            val deferred = snapStore.hasPendingAlert
            val changes = snapStore.observeDiary(diary, now, prefs.scheduleChanges)
            // A deferred event's old time/room may already be obsolete; point to the current schedule.
            val alertText = if (deferred) "Your lesson schedule has changed. Open Gwent Music to see the latest schedule."
                else changes.joinToString("\n")
            if (snapStore.hasPendingAlert && !prefs.inQuietHours(nowMin) &&
                Notifier.notifyScheduleChange(applicationContext, alertText))
                snapStore.alertDelivered()

            // 3) Reminder planning.
            NotificationScheduler.planReminders(applicationContext, upcoming.map { it.second }, prefs, now, snapStore.unconfirmedDays())
            Result.success()
        } ?: Result.success()
    }
}
