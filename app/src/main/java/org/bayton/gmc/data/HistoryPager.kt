package org.bayton.gmc.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bayton.gmc.data.model.DiaryEntry
import java.time.LocalDate

data class HistoryPageState(
    val entries: List<DiaryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedFrom: LocalDate? = null,
    val hasMore: Boolean = true,
    val autoLoadMore: Boolean = true,
    val unboundedKeys: Set<TermKey> = emptySet(),
)

/**
 * Loads progressively older history in inclusive, non-overlapping date windows.
 *
 * The first request also includes six future months for term labelling; subsequent requests walk
 * backwards three months at a time to [earliest]. An empty window pauses automatic loading, not
 * history itself: the user can continue across holidays or other gaps. Failures retain the previous
 * entries and cursor so retry requests the same period. Cancellation restores state and propagates.
 *
 * Call from one dispatcher (the owning ViewModel uses Main). The loading flag rejects overlapping
 * calls there, but is not a cross-thread mutex. Create a new pager to change the account/date bounds.
 */
class HistoryPager(
    private val today: LocalDate,
    private val earliest: LocalDate,
    private val fetch: suspend (LocalDate, LocalDate) -> List<DiaryEntry>,
) {
    private val _state = MutableStateFlow(HistoryPageState())
    val state = _state.asStateFlow()
    private var nextEnd = today.plusMonths(6)

    suspend fun loadNext() {
        if (_state.value.loading || !_state.value.hasMore) return
        val before = _state.value
        _state.value = before.copy(loading = true, error = null)
        // Include the current term's future span only on the first page, to help term labelling.
        val start = (if (before.loadedFrom == null) today.minusMonths(3)
            else nextEnd.minusMonths(3).plusDays(1)).coerceAtLeast(earliest)
        try {
            val page = fetch(start, nextEnd)
            // Enforce the requested range even if the service ignores its date filters. Disjoint
            // windows avoid boundary duplicates without conflating pupils who share a diary ID.
            val inRange = page.filter {
                val date = AcademicTerms.parse(it.startDateTime)?.toLocalDate()
                date != null && !date.isBefore(start) && !date.isAfter(nextEnd)
            }
            nextEnd = start.minusDays(1)
            _state.value = HistoryPageState(
                entries = before.entries + inRange,
                loadedFrom = start,
                hasMore = start.isAfter(earliest),
                // A gap is not the end of history, but must not cause hundreds of empty requests
                // while the footer stays visible. The user can explicitly continue across gaps.
                autoLoadMore = inRange.any {
                    AcademicTerms.parse(it.startDateTime)!!.toLocalDate().isBefore(today.plusDays(1))
                },
            )
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (_: Exception) {
            _state.value = before.copy(error = "Could not load this period. Please retry.")
        }
    }
}
