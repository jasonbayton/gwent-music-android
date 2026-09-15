package org.bayton.gmc.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReminderPlannerTest {
    private val monday = LocalDate.of(2026, 9, 7)   // a fixed Monday
    private val allOn: (String) -> Boolean = { true }

    private fun lesson(date: LocalDate, hour: Int, child: String, subject: String = "Brass", online: Boolean = false) =
        PlannerLesson(date.atTime(hour, 0), child, subject, "Example Secondary School", online)

    @Test fun `morning reminder fires at the set time on the next lesson day`() {
        val lessons = listOf(lesson(monday.plusDays(1), 8, "Alex"))
        val now = monday.atTime(6, 0)
        val fire = ReminderPlanner.nextMorning(now, lessons, 7 * 60 + 30, allOn)!!
        assertEquals(monday.plusDays(1).atTime(7, 30), fire.fireAt)
        assertEquals(monday.plusDays(1), fire.day)
        assertEquals(1, fire.lessons.size)
    }

    @Test fun `morning reminder skips today once its time has passed`() {
        val lessons = listOf(lesson(monday, 10, "Alex"), lesson(monday.plusDays(2), 9, "Alex"))
        val now = monday.atTime(9, 0)   // already past 07:30 today
        val fire = ReminderPlanner.nextMorning(now, lessons, 7 * 60 + 30, allOn)!!
        assertEquals(monday.plusDays(2).atTime(7, 30), fire.fireAt)
    }

    @Test fun `digest fires the evening before a lesson day`() {
        val lessons = listOf(lesson(monday.plusDays(1), 8, "Alex"))
        val now = monday.atTime(6, 0)
        val fire = ReminderPlanner.nextDigest(now, lessons, 18 * 60, allOn)!!
        assertEquals(monday.atTime(18, 0), fire.fireAt)          // 18:00 the day before
        assertEquals(monday.plusDays(1), fire.day)
    }

    @Test fun `disabled pupil is excluded from the day's lessons`() {
        val lessons = listOf(lesson(monday.plusDays(1), 8, "Alex"), lesson(monday.plusDays(1), 16, "Sam"))
        val fire = ReminderPlanner.nextMorning(monday.atTime(6, 0), lessons, 7 * 60 + 30, pupilEnabled = { it != "Sam" })!!
        assertEquals(1, fire.lessons.size)
        assertEquals("Alex", fire.lessons.first().child)
    }

    @Test fun `a just-passed reminder within grace is still selected for the same day`() {
        val lessons = listOf(lesson(monday, 8, "Alex"))
        val fire = ReminderPlanner.nextMorning(monday.atTime(7, 32), lessons, 7 * 60 + 30, allOn)  // 2 min after 07:30
        assertNotNull(fire)
        assertEquals(monday.atTime(7, 30), fire!!.fireAt)
    }

    @Test fun `a reminder past the grace window rolls to the next lesson day`() {
        val lessons = listOf(lesson(monday, 8, "Alex"), lesson(monday.plusDays(1), 8, "Alex"))
        val fire = ReminderPlanner.nextMorning(monday.atTime(7, 40), lessons, 7 * 60 + 30, allOn)!!  // 10 min after
        assertEquals(monday.plusDays(1).atTime(7, 30), fire.fireAt)
    }

    @Test fun `handled morning occurrence skips to next day even inside grace`() {
        val lessons = listOf(lesson(monday, 8, "Alex"), lesson(monday.plusDays(1), 8, "Alex"))
        val fire = ReminderPlanner.nextMorning(monday.atTime(7, 31), lessons, 450, allOn) { it == monday }!!
        assertEquals(monday.plusDays(1), fire.day)
    }

    @Test fun `handled only occurrence cannot immediately requeue`() {
        assertNull(ReminderPlanner.nextMorning(monday.atTime(7, 30), listOf(lesson(monday, 8, "Alex")), 450, allOn) { true })
    }

    @Test fun `digest grace catchup is retained until occurrence is handled`() {
        val lessons = listOf(lesson(monday.plusDays(1), 8, "Alex"), lesson(monday.plusDays(2), 8, "Alex"))
        val now = monday.atTime(18, 1)
        assertEquals(monday.plusDays(1), ReminderPlanner.nextDigest(now, lessons, 1080, allOn)!!.day)
        val fire = ReminderPlanner.nextDigest(now, lessons, 1080, allOn) { it == monday.plusDays(1) }!!
        assertEquals(monday.plusDays(2), fire.day)
    }

    @Test fun `suppressed digest does not requeue when it is the only occurrence`() {
        assertNull(ReminderPlanner.nextDigest(monday.atTime(18, 0), listOf(lesson(monday.plusDays(1), 8, "Alex")), 1080, allOn) { true })
    }

    @Test fun `digest grace cannot requeue yesterday's occurrence after midnight`() {
        val lessons = listOf(lesson(monday, 8, "Pupil"))
        assertNull(ReminderPlanner.nextDigest(monday.atTime(0, 1), lessons, 1439, allOn))
    }

    @Test fun `digest rolls forward after midnight even within previous occurrence grace`() {
        val lessons = listOf(lesson(monday, 8, "Pupil"), lesson(monday.plusDays(1), 8, "Pupil"))
        val fire = ReminderPlanner.nextDigest(monday.atTime(0, 1), lessons, 1439, allOn)!!
        assertEquals(monday.plusDays(1), fire.day)
        assertEquals(monday.atTime(23, 59), fire.fireAt)
    }

    @Test fun `morning grace cannot select yesterday after midnight`() {
        assertNull(ReminderPlanner.nextMorning(monday.atTime(0, 1), listOf(lesson(monday.minusDays(1), 23, "Pupil")), 1439, allOn))
    }

    @Test fun `no fire when there are no future lessons`() {
        val lessons = listOf(lesson(monday.minusDays(3), 8, "Alex"))
        assertNull(ReminderPlanner.nextMorning(monday.atTime(6, 0), lessons, 7 * 60 + 30, allOn))
    }

    @Test fun `text builders name the child and time and flag online`() {
        val day = monday.plusDays(1)
        val morning = ReminderPlanner.morningText(listOf(lesson(day, 8, "Alex")))
        assertTrue(morning.contains("Alex"))
        assertTrue(morning.contains("08:00"))
        val digest = ReminderPlanner.digestText(listOf(lesson(day, 16, "Sam", subject = "Strings", online = true)))
        assertTrue(digest.contains("Sam - Strings 16:00"))
        assertTrue(digest.contains("(online)"))
    }
}
