package org.bayton.gmc.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.LocalDate

data class HistoryTermBounds(val start: LocalDate, val end: LocalDate)

/**
 * Presents history in explicit batches of four term groups, retaining fetched pages in memory.
 *
 * Discovery reads at most eight date windows and selects up to four groups. Completion is a separate
 * phase: resolve authoritative term bounds and fetch through every selected start date, keeping newly
 * discovered groups buffered. The discovery budget and an older group's presence are never treated
 * as proof of completeness. Missing or inconsistent metadata leaves this same batch retryable.
 * Bounds must contain observed rows and span at most two years to reject corrupt runaway ranges.
 *
 * Like [HistoryPager], this is confined to the owning ViewModel's Main dispatcher. It is not a disk
 * cache: recreation of the destination starts a fresh bounded batch. Failed actions retain their
 * target count so Retry does not accidentally request another four groups.
 */
class HistoryBatches(
    private val pager: HistoryPager,
    private val now: LocalDateTime,
    private val termDates: suspend (TermKey) -> HistoryTermBounds,
) {
    private val _state = MutableStateFlow(HistoryPageState())
    val state = _state.asStateFlow()
    private var target = 0
    private var pendingTarget = false
    private val visibleKeys = linkedSetOf<TermKey>()
    private val bounds = mutableMapOf<TermKey, HistoryTermBounds>()
    private var selectionReady = false
    private var failure: String? = null

    suspend fun loadMore() {
        if (_state.value.loading || (!_state.value.hasMore && !pendingTarget)) return
        if (!pendingTarget) {
            target = visibleKeys.size + 4
            selectionReady = false
        }
        pendingTarget = true
        failure = null
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            var requests = 0
            while (!selectionReady && pastGroups().size <= target && pager.state.value.hasMore && requests < 8) {
                pager.loadNext()
                requests++
                publish(loading = true)
                val page = pager.state.value
                if (page.error != null) return
            }
            publish(loading = true)
            selectionReady = true
            // Discovery's request budget is not a completion boundary. Freeze selected keys, then
            // keep paging to their authoritative start dates without exposing another term group.
            for (key in visibleKeys) {
                // Ad-hoc lessons are not a term and have no authoritative term boundary. Preserve
                // their rows and mark completeness unknown without blocking every older batch.
                if (key.term.isNullOrBlank()) continue
                val dates = bounds[key] ?: termDates(key)
                val observed = pastGroups().first { it.key == key }
                // Validate before caching, so a corrected service response can repair a retry.
                if (!(dates.start.year >= 1 && dates.end >= dates.start &&
                    dates.end <= dates.start.plusYears(2) &&
                    dates.start <= observed.start && dates.end >= observed.end)) {
                    bounds.remove(key)
                    error("Invalid term boundary")
                }
                bounds[key] = dates
            }
            val oldest = visibleKeys.mapNotNull { bounds[it]?.start }.minOrNull()
            while (oldest != null && pager.state.value.loadedFrom!! > oldest) {
                check(pager.state.value.hasMore) { "Term extends beyond history boundary" }
                pager.loadNext()
                publish(loading = true)
                if (pager.state.value.error != null) return
            }
            for (group in pastGroups().filter { it.key in visibleKeys && !it.key.term.isNullOrBlank() }) {
                val dates = bounds.getValue(group.key)
                if (group.start < dates.start || group.end > dates.end) {
                    bounds.remove(group.key)
                    error("Lessons fall outside the supplied term dates")
                }
            }
            pendingTarget = false
        } catch (e: CancellationException) {
            // Keep completed windows but make a cancelled action resumable at the same target.
            publish(loading = false)
            throw e
        } catch (_: Exception) {
            failure = "Could not finish loading these terms. Please retry."
        } finally {
            publish(loading = false)
        }
    }

    private fun pastGroups() = AcademicTerms.group(pager.state.value.entries)
        .filter { group -> group.lessons.any { AcademicTerms.parse(it.startDateTime)?.isBefore(now) == true } }
        .sortedByDescending { it.start }

    private fun publish(loading: Boolean) {
        val groups = pastGroups()
        // A group's start can move backwards as older rows arrive. Never hide an already displayed
        // group just because it now sorts behind a newly discovered overlapping group.
        if (!selectionReady) groups.forEach { if (visibleKeys.size < target) visibleKeys += it.key }
        val page = pager.state.value
        _state.value = page.copy(
            entries = page.entries.filter { TermKey(it.academicYear, it.academicYearTerm) in visibleKeys },
            loading = loading,
            error = failure ?: page.error,
            hasMore = groups.any { it.key !in visibleKeys } || page.hasMore,
            autoLoadMore = false,
            unboundedKeys = visibleKeys.filter { it.term.isNullOrBlank() }.toSet(),
        )
    }
}
