package org.bayton.gmc.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScheduleDiffTest {
    private val now = LocalDateTime.of(2026, 9, 7, 12, 0)

    private fun snap(id: String, start: String, room: String = "X2", child: String = "Alex", subject: String = "Brass") =
        LessonSnap(id, start, room, child, subject)

    private fun map(vararg s: LessonSnap) = s.associateBy { it.id }

    @Test fun `a removed future lesson is reported cancelled`() {
        val old = map(snap("1", "2026-09-10T08:00:00"))
        val msgs = ScheduleSnapshotStore.diff(old, emptyList(), now)
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].contains("cancelled"))
        assertTrue(msgs[0].contains("Alex's Brass"))
    }

    @Test fun `a removed lesson that already elapsed is not reported`() {
        val old = map(snap("1", "2026-09-05T08:00:00"))   // before now
        assertEquals(emptyList<String>(), ScheduleSnapshotStore.diff(old, emptyList(), now))
    }

    @Test fun `a changed start time is reported as a move`() {
        val old = map(snap("1", "2026-09-10T08:00:00"))
        val current = listOf(snap("1", "2026-09-10T09:00:00"))
        val msgs = ScheduleSnapshotStore.diff(old, current, now)
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].contains("moved to"))
    }

    @Test fun `a changed room is reported`() {
        val old = map(snap("1", "2026-09-10T08:00:00", room = "X2"))
        val current = listOf(snap("1", "2026-09-10T08:00:00", room = "Room 5"))
        val msgs = ScheduleSnapshotStore.diff(old, current, now)
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].contains("now in Room 5"))
    }

    @Test fun `a newly added lesson is not reported`() {
        val old = map(snap("1", "2026-09-10T08:00:00"))
        val current = listOf(snap("1", "2026-09-10T08:00:00"), snap("2", "2026-09-11T08:00:00"))
        assertEquals(emptyList<String>(), ScheduleSnapshotStore.diff(old, current, now))
    }

    @Test fun `no changes produce no messages`() {
        val old = map(snap("1", "2026-09-10T08:00:00"))
        assertEquals(emptyList<String>(), ScheduleSnapshotStore.diff(old, listOf(snap("1", "2026-09-10T08:00:00")), now))
    }
}
