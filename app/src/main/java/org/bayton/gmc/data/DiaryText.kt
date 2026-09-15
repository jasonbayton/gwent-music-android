package org.bayton.gmc.data

/**
 * Parsing for the diary "subject" string, e.g. "Brass - TE at Example Secondary School with Taylor Example".
 */
object DiaryText {

    /** Split a diary subject into a clean lesson name and the teacher. */
    fun subjectAndTeacher(raw: String?): Pair<String, String?> {
        val s = raw?.trim().orEmpty()
        val i = s.indexOf(" with ")
        val teacher = if (i >= 0) s.substring(i + 6).trim().ifBlank { null } else null
        val head = (if (i >= 0) s.substring(0, i) else s).substringBefore(" at ").trim()
        return stripTeacherInitials(head, teacher).ifBlank { "Lesson" } to teacher
    }

    /**
     * The diary name often carries a teacher-initials tag, e.g. "Brass - TE". Strip it ONLY when the
     * trailing token matches the teacher's initials, so legitimate names like "Music - Theory" survive.
     */
    private fun stripTeacherInitials(head: String, teacher: String?): String {
        val initials = teacher?.split(Regex("\\s+"))
            ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }?.joinToString("")
            ?.takeIf { it.isNotEmpty() } ?: return head
        val m = Regex("\\s-\\s([A-Za-z]{1,5})$").find(head) ?: return head
        return if (m.groupValues[1].uppercase() == initials) head.substring(0, m.range.first).trim() else head
    }
}
