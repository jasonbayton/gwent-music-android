package org.bayton.gmc.notify

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.LoopbackAuth
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.ui.login.LoginViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Runs real Android preferences, worker code and WorkManager database operations, without HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderWorkIntegrationTest.TestApplication::class)
class ReminderWorkIntegrationTest {
    class Component : GeneratedComponent, ReminderWorker.Deps {
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
    private lateinit var wm: WorkManagerImpl
    private lateinit var prefs: NotificationPrefs
    private val clock = Clock.fixed(Instant.parse("2026-09-07T07:30:00Z"), ZoneOffset.UTC)
    private val now = LocalDateTime.now(clock)

    private fun worker(type: String, day: String): ReminderWorker =
        TestListenableWorkerBuilder<ReminderWorker>(app)
            .setInputData(workDataOf("type" to type, "day" to day, "owner" to app.component.account.snapshot()!!.owner))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == ReminderWorker::class.java.name) ReminderWorker(appContext, workerParameters, clock) else null
            }).build()

    @Before fun setUp() {
        // WorkManager's default reflection factory must still find the production constructor.
        assertNotNull(ReminderWorker::class.java.getConstructor(Context::class.java, WorkerParameters::class.java))
        app = RuntimeEnvironment.getApplication() as TestApplication
        prefs = NotificationPrefs(app)
        prefs.clear()
        ScheduleSnapshotStore(app).clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(app) as WorkManagerImpl
        app.component.account = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        app.component.account.activate("fixture-parent", "parent@example.test")
    }

    @After fun tearDown() {
        wm.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun pending(name: String) = wm.getWorkInfosForUniqueWork(name).get()
        .filter { !it.state.isFinished }

    @Test fun `scheduler stores planned day and skips persisted handled occurrence within grace`() {
        val today = now.toLocalDate()
        prefs.morningMinutes = now.hour * 60 + now.minute
        prefs.dayBefore = false
        val lessons = listOf(today, today.plusDays(1)).map {
            PlannerLesson(it.atTime(23, 59), "Test pupil", "Brass", null, false)
        }
        NotificationScheduler.planReminders(app, lessons, prefs, now)
        val initial = pending("gmc-reminder-morning").single()
        assertEquals(today.toString(), wm.workDatabase.workSpecDao().getWorkSpec(initial.id.toString())!!.input.getString("day"))

        prefs.markReminderFired("MORNING", today.toString())
        // A new wrapper reads the persisted SharedPreferences marker, as a later worker would.
        NotificationScheduler.planReminders(app, lessons, NotificationPrefs(app), now)
        val next = pending("gmc-reminder-morning").single()
        val input = wm.workDatabase.workSpecDao().getWorkSpec(next.id.toString())!!.input
        assertNotEquals(initial.id, next.id)
        assertEquals("MORNING", input.getString("type"))
        assertEquals(today.plusDays(1).toString(), input.getString("day"))
    }

    @Test fun `worker persists handled marker for suppressed empty occurrence`() = runBlocking {
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        val today = now.toLocalDate().toString()
        val worker = worker("MORNING", today)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(NotificationPrefs(app).reminderFired("MORNING", today))
        assertTrue(pending("gmc-reminder-morning").isEmpty())
        verify(app.component.repository).schedule()
        Unit
    }

    @Test fun `stale planned day is not reinterpreted as today's reminder`() = runBlocking {
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        val today = now.toLocalDate()
        val worker = worker("MORNING", today.minusDays(1).toString())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertFalse(NotificationPrefs(app).reminderFired("MORNING", today.toString()))
    }

    @Test fun `quiet morning worker advances real scheduled work beyond grace occurrence`() = runBlocking {
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        prefs.morningMinutes = minute
        prefs.dayBefore = false
        prefs.quietStartMinutes = minute
        prefs.quietEndMinutes = (minute + 2) % 1440
        `when`(app.component.repository.schedule()).thenReturn(listOf(today, today.plusDays(1)).map {
            DiaryEntry(startDateTime = it.atTime(23, 59, 59).toString(), firstName = "Test pupil", subject = "Brass")
        })
        val worker = worker("MORNING", today.toString())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(NotificationPrefs(app).reminderFired("MORNING", today.toString()))
        val next = pending("gmc-reminder-morning").single()
        assertEquals(today.plusDays(1).toString(), wm.workDatabase.workSpecDao().getWorkSpec(next.id.toString())!!.input.getString("day"))
    }

    @Test fun `digest worker advances persisted lesson day after suppression`() = runBlocking {
        val tomorrow = now.toLocalDate().plusDays(1)
        val minute = now.hour * 60 + now.minute
        prefs.morning = false
        prefs.digestMinutes = minute
        prefs.quietStartMinutes = minute
        prefs.quietEndMinutes = (minute + 2) % 1440
        `when`(app.component.repository.schedule()).thenReturn(listOf(tomorrow, tomorrow.plusDays(1)).map {
            DiaryEntry(startDateTime = it.atTime(10, 0).toString(), firstName = "Test pupil", subject = "Brass")
        })
        val worker = worker("DIGEST", tomorrow.toString())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(NotificationPrefs(app).reminderFired("DIGEST", tomorrow.toString()))
        val next = pending("gmc-reminder-digest").single()
        assertEquals(tomorrow.plusDays(1).toString(), wm.workDatabase.workSpecDao().getWorkSpec(next.id.toString())!!.input.getString("day"))
    }

    @Test fun `successful login restores cancelled periodic work without application restart`() = runBlocking {
        NotificationScheduler.schedulePeriodic(app)
        val initial = pending("gmc-sync").single()
        NotificationScheduler.cancelAll(app)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(initial.id).get()!!.state)
        `when`(app.component.repository.resolveContact("parent@example.test", null)).thenReturn("test-contact")
        val login = LoginViewModel(mock(LoopbackAuth::class.java), mock(TokenStore::class.java),
            app.component.account, app.component.repository, app)
        login.mockSignIn()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(login.state.value.done)
        val restored = pending("gmc-sync").single()
        assertNotEquals(initial.id, restored.id)
        assertTrue(wm.workDatabase.workSpecDao().getWorkSpec(restored.id.toString())!!.isPeriodic)
        NotificationScheduler.schedulePeriodic(app)
        assertEquals(restored.id, pending("gmc-sync").single().id)
    }

    @Test fun `unconfirmed empty diary at reminder time retries without consuming occurrence`() = runBlocking {
        val today = now.toLocalDate()
        ScheduleSnapshotStore(app).detectAndCommit(listOf(
            LessonSnap("lesson", today.atTime(10, 0).toString(), "X2", "Fixture", "Brass")
        ), now.minusMinutes(30))
        `when`(app.component.repository.schedule()).thenReturn(emptyList())
        assertEquals(ListenableWorker.Result.retry(), worker("MORNING", today.toString()).doWork())
        assertFalse(prefs.reminderFired("MORNING", today.toString()))
        assertTrue(ScheduleSnapshotStore(app).hasPendingAbsences)
    }

    @Test fun `unrelated future absence does not suppress newly added earlier reminder`() {
        val today = now.toLocalDate()
        val lessons = listOf(PlannerLesson(today.atTime(10, 0), "Fixture", "Brass", null, false))
        prefs.dayBefore = false
        prefs.morningMinutes = 7 * 60 + 30
        NotificationScheduler.planReminders(app, lessons, prefs, now.minusMinutes(10), setOf(today.plusDays(7)))
        val work = pending("gmc-reminder-morning").single()
        assertEquals(today.toString(), wm.workDatabase.workSpecDao().getWorkSpec(work.id.toString())!!.input.getString("day"))
    }

    @Test fun `todays unconfirmed lesson cannot suppress tomorrows digest`() {
        val today = now.toLocalDate()
        prefs.morning = false
        prefs.digestMinutes = 18 * 60
        val lessons = listOf(PlannerLesson(today.plusDays(1).atTime(10, 0), "Fixture", "Brass", null, false))
        NotificationScheduler.planReminders(app, lessons, prefs, today.atTime(17, 50), setOf(today))
        val work = pending("gmc-reminder-digest").single()
        assertEquals(today.plusDays(1).toString(), wm.workDatabase.workSpecDao().getWorkSpec(work.id.toString())!!.input.getString("day"))
    }
}
