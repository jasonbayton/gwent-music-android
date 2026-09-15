package org.bayton.gmc.notify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import androidx.work.testing.*
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.model.Envelope
import org.bayton.gmc.data.model.Debtor
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.data.model.SecureMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DeferredNotificationsTest.TestApplication::class)
class DeferredNotificationsTest {
    class Component : GeneratedComponent, SyncWorker.Deps, ReminderWorker.Deps {
        val repository = mock(GwentRepository::class.java)
        lateinit var account: Session
        override fun repo() = repository
        override fun session() = account
    }
    class TestApplication : Application(), GeneratedComponentManager<Component> {
        val component = Component()
        override fun generatedComponent() = component
    }
    private lateinit var app: TestApplication
    private val now = LocalDateTime.parse("2026-09-07T22:00:00")
    private val initial = LessonSnap("lesson", "2026-09-10T08:00:00", "X2", "Fixture", "Brass")
    private fun snapshots() = ScheduleSnapshotStore(app)
    private fun notificationCount() = shadowOf(app.getSystemService(NotificationManager::class.java)).size()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TestApplication
        app.component.account = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        app.component.account.activate("fixture-parent", "parent@example.test")
        NotificationPrefs(app).clear(); SeenStore(app).clear(); snapshots().clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun cleanup() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private suspend fun sync(time: String): ListenableWorker.Result {
        val clock = Clock.fixed(Instant.parse(time), ZoneOffset.UTC)
        val worker = TestListenableWorkerBuilder<SyncWorker>(app)
            .setInputData(workDataOf("owner" to app.component.account.snapshot()!!.owner))
            .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                if (name == SyncWorker::class.java.name) SyncWorker(context, params, clock) else null
        }).build()
        return worker.doWork()
    }

    @Test fun `one absent observation and immediate duplicate cannot announce cancellation`() {
        snapshots().detectAndCommit(listOf(initial), now)
        assertTrue(snapshots().detectAndCommit(emptyList(), now.plusMinutes(1)).isEmpty())
        assertTrue(snapshots().detectAndCommit(emptyList(), now.plusMinutes(2)).isEmpty())
        assertTrue(snapshots().hasPendingAbsences)
        assertFalse(snapshots().hasPendingAlert)
    }
    @Test fun `confirmed absence after fifteen minutes announces once across store recreation`() {
        snapshots().detectAndCommit(listOf(initial), now)
        snapshots().detectAndCommit(emptyList(), now.plusMinutes(1))
        assertEquals(1, snapshots().detectAndCommit(emptyList(), now.plusMinutes(16)).size)
        assertFalse(snapshots().hasPendingAbsences)
        assertTrue(snapshots().hasPendingAlert)
        assertTrue(snapshots().detectAndCommit(emptyList(), now.plusMinutes(31)).isEmpty())
    }
    @Test fun `reappearance discards suspected absence without cancellation`() {
        snapshots().detectAndCommit(listOf(initial), now)
        snapshots().detectAndCommit(emptyList(), now.plusMinutes(1))
        assertTrue(snapshots().detectAndCommit(listOf(initial), now.plusMinutes(20)).isEmpty())
        assertFalse(snapshots().hasPendingAbsences)
        assertFalse(snapshots().hasPendingAlert)
    }
    @Test fun `quiet hours persist message and schedule alerts until first daytime sync`() = runBlocking {
        SeenStore(app).commit(emptySet())
        snapshots().detectAndCommit(listOf(initial), now)
        `when`(app.component.repository.messages(false)).thenReturn(listOf(SecureMessage(subject = "Fixture", dateStamp = "2026-09-07T22:00:00")))
        `when`(app.component.repository.schedule()).thenReturn(listOf(DiaryEntry(diaryID = "lesson", startDateTime = "2026-09-10T09:00:00")))
        assertEquals(ListenableWorker.Result.success(), sync("2026-09-07T22:00:00Z"))
        assertEquals(0, notificationCount())
        assertEquals(1, SeenStore(app).pending().size)
        assertTrue(snapshots().hasPendingAlert)
        assertEquals(ListenableWorker.Result.success(), sync("2026-09-08T07:00:00Z"))
        assertEquals(2, notificationCount())
        assertTrue(SeenStore(app).pending().isEmpty())
        assertFalse(snapshots().hasPendingAlert)
        Notifier.cancelAll(app)
        sync("2026-09-08T07:30:00Z")
        assertEquals(0, notificationCount())
    }
    @Test fun `disabled preferences discard queued alerts deliberately`() {
        val seen = SeenStore(app)
        seen.commit(emptySet()); seen.observe(setOf("new"), true)
        assertTrue(seen.observe(setOf("new"), false).isEmpty())
        snapshots().detectAndCommit(listOf(initial), now)
        snapshots().detectAndCommit(listOf(initial.copy(room = "X3")), now)
        snapshots().detectAndCommit(listOf(initial.copy(room = "X3")), now, alertsEnabled = false)
        assertFalse(snapshots().hasPendingAlert)
    }
    @Test fun `blocked notification permission retains pending events for later delivery`() = runBlocking {
        SeenStore(app).commit(emptySet())
        `when`(app.component.repository.messages(false)).thenReturn(listOf(SecureMessage(subject = "Fixture")))
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        manager.setNotificationsEnabled(false)
        sync("2026-09-08T08:00:00Z")
        assertEquals(1, SeenStore(app).pending().size)
        manager.setNotificationsEnabled(true)
        sync("2026-09-08T08:30:00Z")
        assertTrue(SeenStore(app).pending().isEmpty())
        assertEquals(1, notificationCount())
    }
    @Test fun `a read inbox backlog never alerts as new even from an empty baseline`() = runBlocking {
        // The reported bug: an empty baseline (a first sync that returned nothing while the service
        // was flaky) followed by a fetch of an all-READ inbox must NOT fire a "new message" alert.
        SeenStore(app).commit(emptySet())
        val readBacklog = (1..48).map {
            SecureMessage(subject = "Newsletter $it", dateStamp = "2026-09-0${it % 9 + 1}T09:00:00",
                fromContactID = "office", firstName = "Office", viewedDate = "2026-09-06T09:00:00")
        }
        `when`(app.component.repository.messages(false)).thenReturn(readBacklog)
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        sync("2026-09-08T08:00:00Z")
        assertEquals(0, notificationCount())
        assertTrue(SeenStore(app).pending().isEmpty())
    }

    @Test fun `a genuinely unread message still alerts from an empty baseline`() = runBlocking {
        // Positive control: the fix must filter by read-state, not suppress everything.
        SeenStore(app).commit(emptySet())
        `when`(app.component.repository.messages(false)).thenReturn(listOf(
            SecureMessage(subject = "Autumn timetable", dateStamp = "2026-09-08T07:00:00", fromContactID = "office")))
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        sync("2026-09-08T08:00:00Z")
        assertEquals(1, notificationCount())
    }

    @Test fun `failed diary preserves saved baseline and existing reminder jobs`() = runBlocking {
        snapshots().detectAndCommit(listOf(initial), now)
        `when`(app.component.repository.messages(false)).thenReturn(emptyList())
        doAnswer { throw java.io.IOException("fixture failure") }.`when`(app.component.repository).schedule()
        val prefs = NotificationPrefs(app)
        NotificationScheduler.planReminders(app, listOf(PlannerLesson(now.plusDays(2), "Fixture", "Brass", null, false)), prefs, now)
        val before = WorkManager.getInstance(app).getWorkInfosForUniqueWork("gmc-reminder-morning").get().map { it.id }
        assertEquals(ListenableWorker.Result.retry(), sync("2026-09-07T22:00:00Z"))
        assertEquals(before, WorkManager.getInstance(app).getWorkInfosForUniqueWork("gmc-reminder-morning").get().map { it.id })
        assertTrue(snapshots().detectAndCommit(listOf(initial), now.plusMinutes(30)).isEmpty())
    }

    private fun seedAccountState() {
        SeenStore(app).commit(emptySet())
        SeenStore(app).observe(setOf("old-pending"), true)
        snapshots().detectAndCommit(listOf(initial), now)
        snapshots().detectAndCommit(listOf(initial.copy(room = "changed")), now)
        NotificationPrefs(app).apply {
            messages = false
            setPupilEnabled("Fixture", false)
            markReminderFired("MORNING", "2026-09-08")
        }
    }

    @Test fun `terminal expiry and same account reauth preserve notification state across recreation`() {
        seedAccountState()
        val oldRun = app.component.account.snapshot()!!
        app.component.account.clear()
        assertNull(app.component.account.ifCurrent(oldRun) { fail("Expired worker published") })
        app.component.account = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        app.component.account.activate("fixture-parent", "parent@example.test")
        assertEquals(setOf("old-pending"), SeenStore(app).pending())
        assertTrue(snapshots().hasPendingAlert)
        assertFalse(NotificationPrefs(app).messages)
        assertFalse(NotificationPrefs(app).pupilEnabled("Fixture"))
        assertTrue(NotificationPrefs(app).reminderFired("MORNING", "2026-09-08"))
    }

    @Test fun `different account gets clean state and its first inbox is only a baseline`() = runBlocking {
        seedAccountState()
        app.component.account.clear()
        app.component.account.activate("other-parent", "other@example.test")
        assertFalse(SeenStore(app).isInitialised)
        assertFalse(snapshots().isInitialised)
        assertFalse(snapshots().hasPendingAlert)
        assertTrue(NotificationPrefs(app).messages)
        assertTrue(NotificationPrefs(app).pupilEnabled("Fixture"))
        assertFalse(NotificationPrefs(app).reminderFired("MORNING", "2026-09-08"))
        `when`(app.component.repository.messages(false)).thenReturn(listOf(SecureMessage(subject = "Existing inbox")))
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        sync("2026-09-08T08:00:00Z")
        assertTrue(SeenStore(app).isInitialised)
        assertTrue(SeenStore(app).pending().isEmpty())
        assertEquals(0, notificationCount())
    }

    @Test fun `account change during message fetch cannot publish the old response`() = runBlocking {
        doAnswer {
            app.component.account.clear()
            app.component.account.activate("other-parent", "other@example.test")
            listOf(SecureMessage(subject = "Old response"))
        }.`when`(app.component.repository).messages(false)
        assertEquals(ListenableWorker.Result.success(), sync("2026-09-08T08:00:00Z"))
        assertFalse(SeenStore(app).isInitialised)
        verify(app.component.repository, never()).schedule()
        assertEquals(0, notificationCount())
    }

    @Test fun `account change during diary fetch cannot restore old snapshots or reminders`() = runBlocking {
        `when`(app.component.repository.messages(false)).thenReturn(emptyList())
        doAnswer {
            app.component.account.clear()
            app.component.account.activate("other-parent", "other@example.test")
            listOf(DiaryEntry(diaryID = "old", startDateTime = "2026-09-10T08:00:00", firstName = "Fixture"))
        }.`when`(app.component.repository).schedule()
        assertEquals(ListenableWorker.Result.success(), sync("2026-09-08T08:00:00Z"))
        assertFalse(snapshots().isInitialised)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("gmc-reminder-morning").get().isEmpty())
        assertEquals(0, notificationCount())
    }

    @Test fun `same account reauth still invalidates an old in flight worker`() = runBlocking {
        doAnswer {
            app.component.account.clear()
            app.component.account.activate("fixture-parent", "parent@example.test")
            listOf(SecureMessage(subject = "Old response"))
        }.`when`(app.component.repository).messages(false)
        assertEquals(ListenableWorker.Result.success(), sync("2026-09-08T08:00:00Z"))
        assertFalse(SeenStore(app).isInitialised)
    }

    @Test fun `queued reminders from another account and legacy unowned work are rejected`() = runBlocking {
        val owner = app.component.account.snapshot()!!.owner
        app.component.account.clear()
        app.component.account.activate("other-parent", "other@example.test")
        for (oldOwner in listOf(owner, null)) {
            val worker = TestListenableWorkerBuilder<ReminderWorker>(app)
                .setInputData(workDataOf("owner" to oldOwner, "type" to "MORNING", "day" to "2026-09-08"))
                .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }
        verifyNoInteractions(app.component.repository)
        assertFalse(NotificationPrefs(app).reminderFired("MORNING", "2026-09-08"))
    }

    @Test fun `explicit disconnect finishes cleanup before a subsequent login`() {
        seedAccountState()
        app.component.account.disconnect()
        app.component.account.activate("other-parent", "other@example.test")
        SeenStore(app).commit(setOf("new-baseline"))
        assertEquals(setOf("new-baseline"), SeenStore(app).seen())
        assertTrue(NotificationPrefs(app).pupilEnabled("Fixture"))
    }

    @Test fun `old repository response cannot repopulate message cache after account transition`() = runBlocking {
        val api = mock(ParitorApi::class.java)
        val session = app.component.account
        val repo = GwentRepository(api, session)
        doAnswer {
            session.clear()
            repo.clearCaches()
            session.activate("other-parent", "other@example.test")
            Envelope(listOf(SecureMessage(contactActivityItem = "old-item", subject = "Old response")), success = true)
        }.`when`(api).messagesReceived("fixture-parent", take = 50, includeRead = true)
        `when`(api.messagesReceived("other-parent", take = 50, includeRead = true))
            .thenReturn(Envelope(emptyList(), success = true))
        assertTrue(repo.messages(false).isEmpty())
        assertNull(repo.messageByItem("received", "old-item"))
    }

    @Test fun `queued sync from previous account cannot fetch the new account`() = runBlocking {
        val owner = app.component.account.snapshot()!!.owner
        app.component.account.clear()
        app.component.account.activate("other-parent", "other@example.test")
        val worker = TestListenableWorkerBuilder<SyncWorker>(app)
            .setInputData(workDataOf("owner" to owner)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        verifyNoInteractions(app.component.repository)
    }

    @Test fun `unattributed legacy state is reset before first owned session is exposed`() {
        seedAccountState()
        app.getSharedPreferences("gmc_notification_owner", Context.MODE_PRIVATE).edit().clear().commit()
        app.component.account.snapshot()
        assertFalse(SeenStore(app).isInitialised)
        assertFalse(snapshots().isInitialised)
        assertTrue(NotificationPrefs(app).pupilEnabled("Fixture"))
        assertNotNull(NotificationAccountState(app).owner)
    }

    @Test fun `old debtor response cannot replace new account billing pivot`() = runBlocking {
        val api = mock(ParitorApi::class.java)
        val session = app.component.account
        val repo = GwentRepository(api, session)
        doAnswer {
            session.clear()
            session.activate("other-parent", "other@example.test")
            session.debtorId = "new-debtor"
            Envelope(listOf(Debtor(debtor = "old-debtor")), success = true)
        }.`when`(api).debtorByEmail("parent@example.test")
        assertNull(repo.debtor())
        assertEquals("new-debtor", session.debtorId)
    }
}
