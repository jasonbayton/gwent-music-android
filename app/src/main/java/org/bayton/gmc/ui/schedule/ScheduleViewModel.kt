package org.bayton.gmc.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.cache.Connectivity
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.data.cache.FreshnessClassifier
import org.bayton.gmc.data.cache.SnapshotStore
import org.bayton.gmc.data.model.DiaryEntry
import java.time.LocalDate
import javax.inject.Inject

data class ScheduleUi(
    val items: List<DiaryEntry> = emptyList(),
    val loading: Boolean = true,   // before the first (near-term) window arrives with no cache to show
    val error: String? = null,     // the near-term window failed and there was no cache: nothing to show
    val partial: Boolean = false,  // a later window failed: showing what loaded (completeness, not staleness)
    val freshness: Freshness = Freshness.SYNCING,
    val syncedAt: Long? = null,
)

/** The persisted Schedule agenda (FR-D01). */
@Serializable
data class ScheduleSnapshot(val items: List<DiaryEntry> = emptyList())

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repo: GwentRepository,
    private val snapshots: SnapshotStore,
    private val connectivity: Connectivity,
) : ViewModel() {
    private val _ui = MutableStateFlow(ScheduleUi())
    val ui = _ui.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init { load() }

    /**
     * Cache-first (FR-D01): render the saved agenda immediately, then refresh in narrow, disjoint
     * windows (near term first, then extensions). On a near-window failure keep the cached agenda
     * showing with a freshness state (FR-D02) instead of a blank error.
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = snapshots.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer())
            var shown: List<DiaryEntry> = cached?.value?.items.orEmpty()
            _ui.value = if (cached != null)
                ScheduleUi(items = shown, loading = false, freshness = Freshness.SYNCING, syncedAt = cached.syncedAt)
            else ScheduleUi(loading = true, freshness = Freshness.SYNCING)

            // Sample the clock AFTER the cache read (as the original did): the read can suspend, and a
            // read crossing midnight must not shift the refresh windows relative to the old behaviour.
            val today = LocalDate.now()
            val spanStart = today.minusDays(150)   // the whole refreshed span (union of all windows below)
            val spanEnd = today.plusMonths(6)
            val nearStart = today.minusDays(31)
            val nearEnd = today.plusDays(62)
            val near = attempt { window(nearStart, nearEnd) }
            if (near.isFailure) {
                val e = near.exceptionOrNull()!!
                if (cached != null) _ui.update { it.copy(loading = false, freshness = FreshnessClassifier.classify(e, connectivity.isOnline())) }
                else _ui.value = ScheduleUi(loading = false, error = "Couldn't load your schedule")
                return@launch
            }
            // Merge the near window into the shown agenda rather than replacing the whole list. A
            // replace briefly dropped the cached later-term lessons, collapsing the agenda to a single
            // term and removing the leading term divider, so the list jumped. Clamp to the refreshed
            // span (dropping unparseable dates and anything a full replace would also drop) and swap
            // only the near window's own range, so the cached later-term lessons stay put. The final
            // agenda matches a full replace: every in-span row is covered by exactly one window below.
            shown = mergeWindow(shown.filter { it.inRange(spanStart, spanEnd) }, near.getOrDefault(emptyList()), nearStart, nearEnd)
            _ui.value = ScheduleUi(items = shown, loading = false, freshness = Freshness.CURRENT, syncedAt = System.currentTimeMillis())

            // Disjoint, locally-clamped windows (<= ~4 months each) covering the whole today-150 .. today+6mo span.
            val extensions = listOf(
                nearEnd.plusDays(1) to spanEnd,       // further future terms
                spanStart to nearStart.minusDays(1),  // this term's earlier completed lessons
            )
            var partial = false
            for ((start, end) in extensions) {
                val more = attempt { window(start, end) }
                if (more.isFailure) partial = true
                // Success swaps this window's range for fresh lessons; failure clears it (contributing
                // nothing, exactly as before). Only this window's own range changes - terms already
                // shown from earlier windows are untouched.
                shown = mergeWindow(shown, more.getOrDefault(emptyList()), start, end)
                _ui.update { it.copy(items = shown) }
            }
            // Persist only a COMPLETE agenda. Overwriting the saved snapshot with a partial refresh
            // (near ok, an extension failed) would drop previously-known lessons from the cache and,
            // since `partial` is not persisted, restore them later looking complete.
            if (!partial) snapshots.write(SnapshotStore.SCHEDULE, ScheduleSnapshot(shown), ScheduleSnapshot.serializer())
            if (partial) _ui.update { it.copy(partial = true) }
        }
    }

    /** Fetch one window, clamped locally to [start, end] inclusive so windows stay disjoint and append. */
    private suspend fun window(start: LocalDate, end: LocalDate): List<DiaryEntry> =
        repo.diaryBetween(start, end).filter { it.inRange(start, end) }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
}

private fun DiaryEntry.dayOrNull(): LocalDate? =
    startDateTime?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** True when this lesson's date is within [start, end] inclusive (and has a parseable date at all). */
private fun DiaryEntry.inRange(start: LocalDate, end: LocalDate): Boolean =
    dayOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true

/**
 * Replace only the [start, end] date slice of [existing] with [fresh], keeping entries outside that
 * range untouched (e.g. a later term already shown from the cache). Windows are disjoint and tile the
 * whole refreshed span, so after every window has merged the result equals a full replace - but a term
 * already shown from the cache is not dropped and re-added as each window lands, so the list does not
 * jump. (A window that fails still clears its own range, exactly as a replace would.)
 */
internal fun mergeWindow(existing: List<DiaryEntry>, fresh: List<DiaryEntry>, start: LocalDate, end: LocalDate): List<DiaryEntry> =
    existing.filterNot { it.inRange(start, end) } + fresh
