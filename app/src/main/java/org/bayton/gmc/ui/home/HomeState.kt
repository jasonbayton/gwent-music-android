package org.bayton.gmc.ui.home

import kotlinx.serialization.Serializable
import org.bayton.gmc.data.cache.Freshness

/**
 * One dashboard module's load lifecycle. Each Home module loads and displays independently, so a
 * single slow or failed request (the diary endpoint can be slow over a wide range) degrades to its
 * own retryable card rather than timing out the whole page.
 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<out T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Serializable
data class NextLesson(
    val subject: String, val location: String?, val room: String?,
    val dateLabel: String, val timeLabel: String, val relative: String,
    val teacher: String?, val teacherContactId: String? = null,
    val startIso: String? = null,   // absolute start, so cached date/relative labels can be recomputed
)

/** One row in the multi-pupil "Next up" list; the child's name distinguishes whose lesson it is. */
@Serializable
data class UpcomingLesson(
    val child: String,
    val subject: String,
    val teacher: String?,
    val location: String?,
    val dateLabel: String,
    val timeLabel: String,
    val relative: String,
    val teacherContactId: String? = null,
    val startIso: String? = null,   // absolute start, so cached date/relative labels can be recomputed
)

@Serializable
data class AttentionRow(
    val icon: String, val title: String, val detail: String, val count: Int, val route: String,
)

/** The persisted Home dashboard (FR-D01): what the header, hero/next-up, dots and banner need. */
@Serializable
data class HomeSnapshot(
    val who: String = "",
    val next: NextLesson? = null,
    val nextUp: List<UpcomingLesson> = emptyList(),
    val termTotal: Int = 0,
    val termCompleted: Int = 0,
    val attention: List<AttentionRow> = emptyList(),
)

/** The fast primary module: who the account is for, and the soonest upcoming lessons. Sourced from a
 *  narrow forward-only diary window (next/nextUp only ever use lessons after now). */
data class Overview(
    val who: String = "",
    val next: NextLesson? = null,           // shown for a single-pupil account
    val nextUp: List<UpcomingLesson> = emptyList(), // shown when the account has more than one pupil
)

/** Term progress: a header dot per planned lesson in the current term, filled for those already gone.
 *  Needs the wide back-window, so it is loaded on its own and never blocks the upcoming lessons. */
data class TermProgress(val total: Int, val completed: Int)

data class HomeUi(
    val greeting: String = "",       // pure/time-based, rendered immediately (no network)
    val dateLine: String = "",       // pure/time-based, rendered immediately (no network)
    val overview: Load<Overview> = Load.Loading,
    val term: Load<TermProgress> = Load.Loading,
    val messages: Load<List<AttentionRow>> = Load.Loading,
    val reauthRequired: Boolean = false,
    val freshness: Freshness = Freshness.SYNCING,  // how current the shown dashboard is (FR-D02)
    val syncedAt: Long? = null,                     // when the shown data was last fetched
)
