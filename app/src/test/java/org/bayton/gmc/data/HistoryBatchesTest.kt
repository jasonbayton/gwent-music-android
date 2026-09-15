package org.bayton.gmc.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.bayton.gmc.data.model.DiaryEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class HistoryBatchesTest {
    private val today = LocalDate.of(2026, 9, 6)
    private fun lesson(date: LocalDate, term: String = date.toString()) = DiaryEntry(
        diaryID = "shared", firstName = "Alex", startDateTime = "${date}T09:00:00",
        academicYear = "year", academicYearTerm = term,
    )
    private fun count(batches: HistoryBatches) = AcademicTerms.group(batches.state.value.entries).size

    @Test fun `ad hoc groups are labelled unknown without blocking older history`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(5)) { start, _ ->
            calls++
            listOf(lesson(start).copy(academicYearTerm = null))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { error("No metadata for ad hoc rows") }
        batches.loadMore()
        assertNull(batches.state.value.error)
        assertEquals(setOf(TermKey("year", null)), batches.state.value.unboundedKeys)
        assertEquals(8, calls)
        batches.loadMore()
        assertEquals(16, calls)
        assertNull(batches.state.value.error)
    }

    @Test fun `older group does not stop completion of the selected four`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(5)) { start, _ ->
            calls++
            when (calls) {
                1 -> (1..4).map { lesson(start.plusDays(it.toLong()), "term$it") }
                2 -> listOf(lesson(start, "older"))
                3 -> listOf(lesson(start, "term4"))
                else -> emptyList()
            }
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) {
            HistoryTermBounds(today.minusMonths(9), today.plusMonths(6))
        }
        batches.loadMore()
        assertEquals(3, calls)
        assertEquals(4, count(batches))
        assertEquals(2, batches.state.value.entries.count { it.academicYearTerm == "term4" })
        assertFalse(batches.state.value.entries.any { it.academicYearTerm == "older" })
        assertNull(batches.state.value.error)
    }

    @Test fun `completion continues past discovery request cap without revealing more groups`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(5)) { start, _ ->
            calls++
            listOf(lesson(start.coerceAtLeast(today.minusMonths(25)), "long-term"))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) {
            HistoryTermBounds(today.minusMonths(25), today.minusMonths(1))
        }
        batches.loadMore()
        assertEquals(9, calls)
        assertEquals(9, batches.state.value.entries.size)
        assertEquals(1, count(batches))
        assertNull(batches.state.value.error)
    }

    @Test fun `metadata failure at EOF is retryable and invalid response is not cached`() = runBlocking {
        var metadataCalls = 0
        val earliest = today.minusMonths(3)
        val pager = HistoryPager(today, earliest) { start, _ -> listOf(lesson(start, "term")) }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) {
            metadataCalls++
            if (metadataCalls == 1) HistoryTermBounds(LocalDate.of(1, 1, 1), LocalDate.of(2, 1, 1))
            else HistoryTermBounds(earliest, today)
        }
        batches.loadMore()
        assertNotNull(batches.state.value.error)
        assertFalse(batches.state.value.hasMore)
        batches.loadMore()
        assertEquals(2, metadataCalls)
        assertNull(batches.state.value.error)
        assertEquals(1, count(batches))
    }

    @Test fun `initial action shows four groups and next action shows four more`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(10)) { start, _ ->
            calls++
            listOf(lesson(start))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        assertEquals(4, count(batches))
        assertEquals(5, calls) // One older group establishes the displayed boundary.
        val first = batches.state.value.entries
        yield()
        assertEquals(5, calls) // No autonomous continuation after publication.
        batches.loadMore()
        assertEquals(8, count(batches))
        assertEquals(9, calls)
        assertTrue(batches.state.value.entries.containsAll(first))
    }

    @Test fun `one response with many terms is buffered and reused`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(2)) { start, _ ->
            calls++
            (0..9).map { lesson(start.plusDays(it.toLong())) }
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        batches.loadMore()
        assertEquals(8, count(batches))
        assertEquals(1, calls)
    }

    @Test fun `empty history is bounded and can be searched further explicitly`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, LocalDate.of(1, 1, 1)) { _, _ -> calls++; emptyList() }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        assertEquals(8, calls)
        assertEquals(0, count(batches))
        assertTrue(batches.state.value.hasMore)
        assertFalse(batches.state.value.loading)
        batches.loadMore()
        assertEquals(16, calls)
    }

    @Test fun `retry keeps target and repeats only failed window`() = runBlocking {
        val calls = mutableListOf<LocalDate>()
        val pager = HistoryPager(today, today.minusYears(10)) { start, _ ->
            calls += start
            if (calls.size == 3) throw IOException("private failure")
            listOf(lesson(start))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        assertEquals(2, count(batches))
        assertNotNull(batches.state.value.error)
        batches.loadMore()
        assertEquals(calls[2], calls[3])
        assertEquals(4, count(batches))
        assertNull(batches.state.value.error)
    }

    @Test fun `duplicate action is ignored and cancellation clears loading`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(1)) { _, _ -> calls++; gate.await(); emptyList() }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        val job = launch { batches.loadMore() }
        yield()
        batches.loadMore()
        assertEquals(1, calls)
        job.cancel()
        job.join()
        assertFalse(batches.state.value.loading)
        assertNull(batches.state.value.error)
        gate.complete(Unit)
        batches.loadMore()
        assertFalse(batches.state.value.hasMore)
    }

    @Test fun `cancellation after partial progress resumes the original four group target`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(10)) { start, _ ->
            calls++
            if (calls == 3) gate.await()
            listOf(lesson(start))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        val job = launch { batches.loadMore() }
        yield()
        assertEquals(2, count(batches))
        job.cancel()
        job.join()
        batches.loadMore()
        assertEquals(4, count(batches))
        assertEquals(6, calls)
    }

    @Test fun `split terms retain every row and stop at earliest boundary`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(1)) { start, _ ->
            calls++
            listOf(lesson(start, "one-term"))
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        assertEquals(1, count(batches))
        assertEquals(calls, batches.state.value.entries.size)
        assertFalse(batches.state.value.hasMore)
        val before = calls
        batches.loadMore()
        assertEquals(before, calls)
    }

    @Test fun `overlapping old rows never hide a previously visible group`() = runBlocking {
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(10)) { start, _ ->
            calls++
            when (calls) {
                1 -> (0..3).map { lesson(start.plusDays(it.toLong()), "term$it") }
                2 -> listOf(lesson(start, "term3"), lesson(start.plusDays(1), "older"))
                else -> emptyList()
            }
        }
        val batches = HistoryBatches(pager, today.atTime(12, 0)) { key -> runCatching { LocalDate.parse(key.term) }.getOrDefault(today.minusYears(1)).let { HistoryTermBounds(it, it.plusYears(1)) } }
        batches.loadMore()
        assertEquals(setOf("term0", "term1", "term2", "term3"),
            batches.state.value.entries.map { it.academicYearTerm }.toSet())
        assertEquals(5, batches.state.value.entries.size)
    }
}
