package org.bayton.gmc.data

import java.time.DayOfWeek
import java.time.LocalDateTime

/** Home-dashboard computations. */
object HomeCompute {

    /** Headline subject for the greeting, aware of how many pupils the account has. */
    fun who(names: List<String>): String = when (names.size) {
        0 -> "Your lessons"
        1 -> "${names[0]}'s lessons"
        2 -> "${names[0]} & ${names[1]}'s lessons"
        else -> "Your pupils' lessons"
    }

    /** Count of distinct upcoming weeks that contain a lesson (one header dot each, regardless of
     *  how many lessons fall in a week). */
    fun upcomingWeeks(starts: List<LocalDateTime>): Int =
        starts.map { it.toLocalDate().with(DayOfWeek.MONDAY) }.distinct().size
}
