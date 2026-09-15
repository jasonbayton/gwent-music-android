package org.bayton.gmc.ui.schedule

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.bayton.gmc.data.AcademicTerms
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.cache.Connectivity
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.data.cache.SnapshotStore
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.notify.NotificationAccountState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * The Schedule agenda loads cache-first then refreshes in disjoint windows. It must MERGE each window
 * into the shown list (swapping only that window's date range) rather than replacing the whole list
 * with the narrow near window - otherwise the cached far-term lessons vanish and reappear mid-refresh,
 * collapsing the term set and making the list jump. These tests pin that behaviour and that the final
 * agenda is identical to a replace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduleLoadMergeTest {
    private val today: LocalDate = LocalDate.now()
    private fun e(date: LocalDate, id: String, year: String? = null, term: String? = null) =
        DiaryEntry(diaryID = id, startDateTime = date.atTime(8, 0).toString(), academicYear = year, academicYearTerm = term)
    private fun DiaryEntry.on(): LocalDate = LocalDate.parse(startDateTime!!.take(10))
    private fun ids(items: List<DiaryEntry>) = items.mapNotNull { it.diaryID }.toSet()

    // ---- mergeWindow: the anti-churn guarantee (deterministic, no Android needed) ----

    @Test fun `merging a window keeps lessons outside its range`() {
        val near = e(today.plusDays(3), "near")        // inside the near window
        val far = e(today.plusMonths(4), "far")        // a later term, OUTSIDE the near window
        val merged = mergeWindow(listOf(near, far), listOf(e(today.plusDays(4), "near2")),
            today.minusDays(31), today.plusDays(62))
        assertTrue("a later-term lesson must NOT be dropped by a near-window merge", "far" in ids(merged))
        assertFalse("the in-range stale row is replaced", "near" in ids(merged))
        assertTrue("the fresh in-range row is present", "near2" in ids(merged))
    }

    @Test fun `an empty fresh set clears only the merged range`() {
        val existing = listOf(e(today.plusDays(3), "inrange"), e(today.plusMonths(4), "far"))
        val merged = mergeWindow(existing, emptyList(), today.minusDays(31), today.plusDays(62))
        assertFalse("in-range row cleared when the window returns nothing", "inrange" in ids(merged))
        assertTrue("out-of-range row survives an empty window", "far" in ids(merged))
    }

    // ---- ScheduleViewModel: final agenda unchanged, far term never dropped ----

    /** Register an any() matcher but return a non-null stand-in, so stubbing a Kotlin method with
     *  non-null parameters does not trip the compiler's parameter null-check (as mockito-kotlin does). */
    private fun <T> anyArg(stub: T): T { any<Any>(); return stub }

    /** In-memory DataStore so cache reads/writes run synchronously on the test coroutine (the real
     *  DataStore does IO on a background dispatcher the Robolectric looper cannot drain). */
    private class MemStore : DataStore<Preferences> {
        private var prefs: Preferences = emptyPreferences()
        override val data: Flow<Preferences> get() = flow { emit(prefs) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(prefs).also { prefs = it }
    }

    @Test fun `load merges windows - far term stays through the near refresh and the final set is unchanged`() = runBlocking<Unit> {
        val app: Context = RuntimeEnvironment.getApplication()
        val session = Session(app.getSharedPreferences("sched_merge", Context.MODE_PRIVATE), NotificationAccountState(app))
        session.activate("owner-A", "a@example.test")
        val store = SnapshotStore(MemStore(), session)

        // Two terms: Autumn (near + past) and Spring (the later, far-future term).
        val nearLesson = e(today.plusDays(7), "near", "2026", "AUT")
        val pastLesson = e(today.minusDays(60), "past", "2026", "AUT")
        val farStale = e(today.plusMonths(4), "far-stale", "2026", "SPR")            // cached Spring lesson
        val farFresh = e(today.plusMonths(4).plusDays(1), "far-fresh", "2026", "SPR") // what the extension returns
        // Seed the cache with a COMPLETE, multi-term agenda (what a prior full load persisted).
        store.write(SnapshotStore.SCHEDULE, ScheduleSnapshot(listOf(nearLesson, farStale, pastLesson)), ScheduleSnapshot.serializer())
        // Fresh server data: near unchanged, the Spring term refreshed to farFresh, past unchanged.
        val corpus = listOf(nearLesson, farFresh, pastLesson)

        val repo = mock(GwentRepository::class.java)
        // diaryBetween returns the corpus rows that fall in the requested window; the far-future
        // extension is gated so we can inspect the list AFTER the near window but BEFORE it merges.
        val farGate = CompletableDeferred<Unit>()
        `when`(repo.diaryBetween(anyArg(today), anyArg(today))).thenAnswer { inv ->
            val start = inv.getArgument<LocalDate>(0)
            val end = inv.getArgument<LocalDate>(1)
            val hit = corpus.filter { val d = it.on(); !d.isBefore(start) && !d.isAfter(end) }
            @Suppress("UNCHECKED_CAST")
            val cont = inv.rawArguments.last() as Continuation<List<DiaryEntry>>
            if (start.isAfter(today)) (suspend { farGate.await(); hit }).startCoroutineUninterceptedOrReturn(cont)
            else hit
        }

        val vm = ScheduleViewModel(repo, store, mock(Connectivity::class.java))
        // ScheduleViewModel's scope is viewModelScope; drive it via the main looper.
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        fun terms() = AcademicTerms.group(vm.ui.value.items).size
        try {
            drain()   // seed cache, run near window; far-future extension is now suspended on farGate
            assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
            assertTrue("the near lesson refreshed in place", "near" in ids(vm.ui.value.items))
            assertTrue("the cached Spring lesson must remain while the near window refreshes",
                "far-stale" in ids(vm.ui.value.items))
            assertEquals("both terms - so both dividers - must survive the near refresh", 2, terms())

            farGate.complete(Unit); drain()   // release the extensions
            // Final agenda == near + refreshed Spring + past: the stale Spring row is replaced by the
            // fresh one (proving the extension actually completed and merged), no duplicates, nothing
            // stale left behind - the same set a replace-then-append would have produced.
            assertEquals(setOf("near", "far-fresh", "past"), ids(vm.ui.value.items))
            assertFalse("the stale cached Spring row is gone", "far-stale" in ids(vm.ui.value.items))
            assertEquals(2, terms())
            assertFalse(vm.ui.value.partial)
            // A complete refresh persists, and the persisted set matches what is shown.
            val persisted = store.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer())
            assertEquals(setOf("near", "far-fresh", "past"), ids(persisted?.value?.items.orEmpty()))
        } finally { vm.viewModelScope.cancel(); drain() }
    }
}
