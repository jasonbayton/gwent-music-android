package org.bayton.gmc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiaryTextTest {

    @Test fun `strips the teacher-initials tag when it matches the teacher`() {
        val (subject, teacher) = DiaryText.subjectAndTeacher("Brass - TE at Example Secondary School with Taylor Example")
        assertEquals("Brass", subject)
        assertEquals("Taylor Example", teacher)
    }

    @Test fun `strips two-word teacher initials`() {
        assertEquals("Strings", DiaryText.subjectAndTeacher("Strings - CE at Example Studio with Casey Example").first)
    }

    // Negative control: a real word after " - " that is NOT the teacher's initials must survive.
    @Test fun `keeps a curriculum word that is not the teacher initials`() {
        assertEquals("Music - Theory", DiaryText.subjectAndTeacher("Music - Theory at School with John Smith").first)
        assertEquals("Violin - GCSE", DiaryText.subjectAndTeacher("Violin - GCSE at School with Casey Example").first)
    }

    @Test fun `handles a subject with no teacher clause`() {
        val (subject, teacher) = DiaryText.subjectAndTeacher("Brass at Example Secondary School")
        assertEquals("Brass", subject)
        assertNull(teacher)
    }

    @Test fun `does not strip when there is no teacher to match against`() {
        // Without a teacher we cannot confirm the token is initials, so leave it intact.
        assertEquals("Brass - TE", DiaryText.subjectAndTeacher("Brass - TE at Example Secondary School").first)
    }

    @Test fun `falls back to a label for a blank subject`() {
        assertEquals("Lesson", DiaryText.subjectAndTeacher("").first)
        assertEquals("Lesson", DiaryText.subjectAndTeacher(null).first)
    }
}
