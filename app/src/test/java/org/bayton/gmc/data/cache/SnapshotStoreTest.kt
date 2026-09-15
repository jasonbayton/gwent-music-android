package org.bayton.gmc.data.cache

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.notify.NotificationAccountState
import org.bayton.gmc.ui.schedule.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)  // plain app: no Hilt/Keystore startup
class SnapshotStoreTest {
    private lateinit var app: Context
    private lateinit var session: Session
    private lateinit var store: SnapshotStore
    private val lesson = ScheduleSnapshot(listOf(DiaryEntry(diaryID = "x", startDateTime = "2026-09-10T08:00:00")))

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        session = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        store = SnapshotStore(app, session)
        runBlocking { store.clear() }
    }

    @Test fun `round-trips a snapshot for the signed-in account`() = runBlocking {
        session.activate("owner-A", "a@example.test")
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        val read = store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer())
        assertEquals(1, read?.value?.items?.size)
        assertEquals("x", read?.value?.items?.first()?.diaryID)
    }

    @Test fun `another account cannot read the first account's snapshot`() = runBlocking {
        session.activate("owner-A", "a@example.test")
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        session.clear()
        session.activate("owner-B", "b@example.test")
        assertNull(store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer()))
    }

    @Test fun `a signed-out user has nothing to read or write`() = runBlocking {
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())   // no-op: no owner
        assertNull(store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer()))
    }

    @Test fun `clear wipes snapshots`() = runBlocking {
        session.activate("owner-A", "a@example.test")
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        store.clear()
        assertNull(store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer()))
    }

    @Test fun `disconnect then late write cannot repopulate real store`() = runBlocking<Unit> {
        session.activate("owner-A", "a@example.test")
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        session.disconnect()
        store.clear()
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        session.activate("owner-A", "a@example.test")
        assertNull(store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer()))
    }

    @Test fun `recreated wrappers retain owner and disk snapshot`() = runBlocking<Unit> {
        session.activate("owner-A", "a@example.test")
        store.write(SnapshotStore.SCHEDULE, lesson, ScheduleSnapshot.serializer())
        val restored = Session(app.getSharedPreferences("test_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        assertEquals(session.snapshot()?.owner, restored.snapshot()?.owner)
        assertEquals(lesson, SnapshotStore(app, restored).read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer())?.value)
    }
}
