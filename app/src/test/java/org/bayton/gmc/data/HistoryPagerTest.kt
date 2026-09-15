package org.bayton.gmc.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.bayton.gmc.data.model.DiaryEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class HistoryPagerTest {
    private val today = LocalDate.of(2026, 9, 6)
    private fun entry(date: LocalDate, child: String = "Alex") = DiaryEntry(
        diaryID = "shared-id", firstName = child, startDateTime = "${date}T09:00:00",
        academicYear = "year", academicYearTerm = "term",
    )

    @Test fun `windows are bounded contiguous and end at the retention boundary`() = runBlocking {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val earliest = today.minusYears(1)
        val pager = HistoryPager(today, earliest) { a, b -> calls += a to b; emptyList() }
        while (pager.state.value.hasMore) pager.loadNext()
        assertEquals(today.minusMonths(3) to today.plusMonths(6), calls.first())
        calls.zipWithNext().forEach { (newer, older) -> assertEquals(newer.first.minusDays(1), older.second) }
        assertEquals(earliest, calls.last().first)
        val count = calls.size
        pager.loadNext()
        assertEquals(count, calls.size)
    }

    @Test fun `failure retains data and retry repeats only the failed window`() = runBlocking {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val pager = HistoryPager(today, today.minusYears(1)) { a, b ->
            calls += a to b
            if (calls.size == 2) throw IOException("private server text")
            listOf(entry(a))
        }
        pager.loadNext()
        val first = pager.state.value
        pager.loadNext()
        assertEquals(first.entries, pager.state.value.entries)
        assertEquals(first.loadedFrom, pager.state.value.loadedFrom)
        assertFalse(pager.state.value.error!!.contains("private"))
        pager.loadNext()
        assertEquals(calls[1], calls[2])
        assertEquals(2, pager.state.value.entries.size)
        assertNull(pager.state.value.error)
    }

    @Test fun `empty pages do not terminate and shared diary IDs retain both pupils`() = runBlocking {
        var n = 0
        val pager = HistoryPager(today, today.minusYears(1)) { a, b ->
            n++
            if (n == 1) emptyList() else listOf(entry(a), entry(a, "Sam"), entry(b.plusDays(1)))
        }
        pager.loadNext()
        assertTrue(pager.state.value.hasMore)
        assertFalse(pager.state.value.autoLoadMore)
        pager.loadNext()
        assertTrue(pager.state.value.autoLoadMore)
        assertEquals(listOf("Alex", "Sam"), pager.state.value.entries.map { it.firstName })
    }

    @Test fun `overlapping calls make one request and cancellation permits retry`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pager = HistoryPager(today, today.minusYears(1)) { _, _ -> calls++; gate.await(); emptyList() }
        val job = launch { pager.loadNext() }
        yield()
        pager.loadNext()
        assertEquals(1, calls)
        job.cancel()
        job.join()
        assertFalse(pager.state.value.loading)
        assertNull(pager.state.value.error)
        gate.complete(Unit)
        pager.loadNext()
        assertEquals(2, calls)
    }

    @Test fun `cancellation propagates without advancing cursor`() = runBlocking {
        val pager = HistoryPager(today, today.minusYears(1)) { _, _ -> throw CancellationException() }
        try { pager.loadNext(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(pager.state.value.loadedFrom)
        assertFalse(pager.state.value.loading)
    }
}
