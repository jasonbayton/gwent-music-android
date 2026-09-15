package org.bayton.gmc.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.mockito.stubbing.Answer
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.cache.*
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.data.model.SecureMessage
import org.bayton.gmc.ui.home.*
import org.bayton.gmc.ui.messages.*
import org.bayton.gmc.ui.schedule.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.time.LocalDate

/** Exercise the coordinators, not just cache serialisation or a hand-built UI state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreenLoadingTest {
    private val repo = mock(GwentRepository::class.java)
    private val snapshots = mock(SnapshotStore::class.java)
    private val connectivity = mock(Connectivity::class.java)
    private val models = mutableListOf<ViewModel>()
    private val today = LocalDate.now()

    @Before fun setup() = runBlocking<Unit> {
        `when`(connectivity.isOnline()).thenReturn(true)
        `when`(repo.homeGreeting()).thenReturn("Hello")
        `when`(repo.homeDateLine()).thenReturn("Today")
        `when`(repo.homeOverview()).thenReturn(Overview(who = "Family"))
        `when`(repo.termProgress()).thenReturn(TermProgress(10, 3))
        `when`(repo.homeMessages()).thenReturn(emptyList())
        `when`(repo.messages(false)).thenReturn(emptyList())
        `when`(repo.messages(true)).thenReturn(emptyList())
        for ((start, end) in windows()) `when`(repo.diaryBetween(start, end)).thenReturn(emptyList())
    }

    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; drain() }
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    @Suppress("UNCHECKED_CAST")
    private fun <T> delayed(value: CompletableDeferred<T>) = Answer<Any?> { call ->
        // Suspend the actual caller so job cancellation is exercised, not simulated with a thrown error.
        (suspend { value.await() }).startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<T>)
    }
    private fun <T : ViewModel> track(vm: T): T = vm.also { models += it; drain() }
    private fun windows() = listOf(
        today.minusDays(31) to today.plusDays(62),
        today.plusDays(63) to today.plusMonths(6),
        today.minusDays(150) to today.minusDays(32),
    )
    private fun lesson(day: LocalDate, id: String = day.toString()) =
        DiaryEntry(diaryID = id, startDateTime = "${day}T10:00:00")

    @Test fun `home persists only after every module succeeds`() = runBlocking<Unit> {
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        assertEquals(Load.Ready(TermProgress(10, 3)), vm.ui.value.term)
        verify(snapshots).write(SnapshotStore.HOME,
            HomeSnapshot(who = "Family", termTotal = 10, termCompleted = 3), HomeSnapshot.serializer())
    }

    @Test fun `one failed home module leaves successful siblings visible and does not cache partial data`() = runBlocking<Unit> {
        `when`(repo.termProgress()).thenAnswer { throw SocketTimeoutException() }
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertTrue(vm.ui.value.overview is Load.Ready)
        assertTrue(vm.ui.value.messages is Load.Ready)
        assertTrue(vm.ui.value.term is Load.Failed)
        assertEquals(Freshness.SERVICE_HOLD, vm.ui.value.freshness)
        assertFalse(mockingDetails(snapshots).invocations.any { it.method.name == "write" })
    }

    @Test fun `targeted home retry does not reload successful siblings`() = runBlocking<Unit> {
        `when`(repo.termProgress()).thenAnswer { throw SocketTimeoutException() }.thenReturn(TermProgress(10, 3))
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        vm.retryTerm(); drain()
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        verify(repo, times(2)).termProgress()
        verify(repo, times(1)).homeOverview()
        verify(repo, times(1)).homeMessages()
    }

    @Test fun `targeted retry leaves an in-flight sibling alive`() = runBlocking<Unit> {
        val overview = CompletableDeferred<Overview>()
        `when`(repo.homeOverview()).thenAnswer(delayed(overview))
        `when`(repo.termProgress()).thenAnswer { throw SocketTimeoutException() }.thenReturn(TermProgress(10, 3))
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertTrue(vm.ui.value.overview is Load.Loading)
        assertTrue(vm.ui.value.messages is Load.Ready)
        vm.retryTerm(); drain()
        assertTrue(vm.ui.value.overview is Load.Loading)
        overview.complete(Overview(who = "Completed sibling")); drain()
        assertEquals(Load.Ready(Overview(who = "Completed sibling")), vm.ui.value.overview)
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        verify(repo, times(1)).homeOverview()
    }

    @Test fun `switching inbox tabs cancels old load before it can replace sent items`() = runBlocking<Unit> {
        val received = CompletableDeferred<List<SecureMessage>>()
        `when`(repo.messages(false)).thenAnswer(delayed(received))
        val sent = listOf(SecureMessage(contactActivityItem = "sent"))
        `when`(repo.messages(true)).thenReturn(sent)
        val vm = track(MessagesViewModel(repo, snapshots, connectivity))
        vm.select(true); drain()
        received.complete(listOf(SecureMessage(contactActivityItem = "old inbox"))); drain()
        assertTrue(vm.ui.value.sent)
        assertEquals(sent, vm.ui.value.items)
        assertFalse(mockingDetails(snapshots).invocations.any { it.method.name == "write" })
    }

    @Test fun `missing home session does not start account requests`() = runBlocking<Unit> {
        `when`(repo.requiresReauth()).thenReturn(true)
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertTrue(vm.ui.value.reauthRequired)
        assertEquals(Freshness.REAUTH_REQUIRED, vm.ui.value.freshness)
        verify(repo, never()).homeOverview()
        verify(repo, never()).termProgress()
        verify(repo, never()).homeMessages()
    }

    @Test fun `home authentication failure requests reauthentication`() = runBlocking<Unit> {
        `when`(repo.homeOverview()).thenAnswer { throw ReauthenticationRequired() }
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertEquals(Freshness.REAUTH_REQUIRED, vm.ui.value.freshness)
        assertTrue(vm.ui.value.reauthRequired)
    }

    @Test fun `home authentication failure is not hidden by a later sibling timeout`() = runBlocking<Unit> {
        `when`(repo.homeOverview()).thenAnswer { throw ReauthenticationRequired() }
        `when`(repo.termProgress()).thenAnswer { throw SocketTimeoutException() }
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        assertEquals(Freshness.REAUTH_REQUIRED, vm.ui.value.freshness)
        assertTrue(vm.ui.value.reauthRequired)
    }

    @Test fun `recovered auth module no longer masks a remaining sibling timeout`() = runBlocking<Unit> {
        `when`(repo.homeOverview()).thenAnswer { throw ReauthenticationRequired() }.thenReturn(Overview())
        `when`(repo.termProgress()).thenAnswer { throw SocketTimeoutException() }
        val vm = track(HomeViewModel(repo, snapshots, connectivity))
        vm.retryOverview(); drain()
        assertEquals(Freshness.SERVICE_HOLD, vm.ui.value.freshness)
        assertFalse(vm.ui.value.reauthRequired)
    }

    @Test fun `cached inbox survives offline refresh and retains original timestamp`() = runBlocking<Unit> {
        val old = SecureMessage(contactActivityItem = "old")
        `when`(snapshots.read(SnapshotStore.MESSAGES, MessagesSnapshot.serializer()))
            .thenReturn(Cached(MessagesSnapshot(listOf(old)), 123L))
        `when`(repo.messages(false)).thenAnswer { throw SocketTimeoutException() }
        `when`(connectivity.isOnline()).thenReturn(false)
        val vm = track(MessagesViewModel(repo, snapshots, connectivity))
        assertEquals(listOf(old), vm.ui.value.items)
        assertEquals(123L, vm.ui.value.syncedAt)
        assertEquals(Freshness.OFFLINE, vm.ui.value.freshness)
        assertFalse(vm.ui.value.loading)
        assertNull(vm.ui.value.error)
    }

    @Test fun `sent folder neither reads nor overwrites received snapshot`() = runBlocking<Unit> {
        val vm = track(MessagesViewModel(repo, snapshots, connectivity))
        clearInvocations(snapshots)
        vm.select(true); drain()
        assertTrue(vm.ui.value.sent)
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        verifyNoInteractions(snapshots)
        vm.select(true); drain()
        verify(repo, times(1)).messages(true)
    }

    @Test fun `mark read changes only the selected unread delivery`() = runBlocking<Unit> {
        val unread = SecureMessage(contactActivityItem = "one")
        val read = SecureMessage(contactActivityItem = "two", viewedDate = "server-date")
        `when`(repo.messages(false)).thenReturn(listOf(unread, read))
        val vm = track(MessagesViewModel(repo, snapshots, connectivity))
        vm.markRead("one"); vm.markRead("two"); vm.markRead("missing")
        assertEquals(listOf("read", "server-date"), vm.ui.value.items.map { it.viewedDate })
    }

    @Test fun `empty successful inbox replaces stale messages`() = runBlocking<Unit> {
        `when`(snapshots.read(SnapshotStore.MESSAGES, MessagesSnapshot.serializer()))
            .thenReturn(Cached(MessagesSnapshot(listOf(SecureMessage(contactActivityItem = "gone"))), 123L))
        val vm = track(MessagesViewModel(repo, snapshots, connectivity))
        assertTrue(vm.ui.value.items.isEmpty())
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        verify(snapshots).write(SnapshotStore.MESSAGES, MessagesSnapshot(), MessagesSnapshot.serializer())
    }

    @Test fun `schedule clamps inclusive windows and persists a complete agenda`() = runBlocking<Unit> {
        val expected = mutableListOf<DiaryEntry>()
        for ((start, end) in windows()) {
            val boundaries = listOf(lesson(start), lesson(end))
            expected += boundaries
            `when`(repo.diaryBetween(start, end)).thenReturn(
                listOf(lesson(start.minusDays(1))) + boundaries + lesson(end.plusDays(1)))
        }
        val vm = track(ScheduleViewModel(repo, snapshots, connectivity))
        assertEquals(expected, vm.ui.value.items)
        assertFalse(vm.ui.value.partial)
        verify(snapshots).write(SnapshotStore.SCHEDULE, ScheduleSnapshot(expected), ScheduleSnapshot.serializer())
    }

    @Test fun `failed schedule extension preserves near lessons without overwriting complete cache`() = runBlocking<Unit> {
        val (start, end) = windows()[0]
        val near = listOf(lesson(today))
        `when`(repo.diaryBetween(start, end)).thenReturn(near)
        val (laterStart, laterEnd) = windows()[1]
        `when`(repo.diaryBetween(laterStart, laterEnd)).thenAnswer { throw SocketTimeoutException() }
        val vm = track(ScheduleViewModel(repo, snapshots, connectivity))
        assertEquals(near, vm.ui.value.items)
        assertTrue(vm.ui.value.partial)
        assertFalse(vm.ui.value.loading)
        assertFalse(mockingDetails(snapshots).invocations.any { it.method.name == "write" })
        val (pastStart, pastEnd) = windows()[2]
        verify(repo).diaryBetween(pastStart, pastEnd)
    }

    @Test fun `failed near schedule keeps cached agenda and never requests extensions`() = runBlocking<Unit> {
        val old = listOf(lesson(today.minusDays(1)))
        `when`(snapshots.read(SnapshotStore.SCHEDULE, ScheduleSnapshot.serializer()))
            .thenReturn(Cached(ScheduleSnapshot(old), 123L))
        val (start, end) = windows()[0]
        `when`(repo.diaryBetween(start, end)).thenAnswer { throw ReauthenticationRequired() }
        val vm = track(ScheduleViewModel(repo, snapshots, connectivity))
        assertEquals(old, vm.ui.value.items)
        assertEquals(123L, vm.ui.value.syncedAt)
        assertEquals(Freshness.REAUTH_REQUIRED, vm.ui.value.freshness)
        val (laterStart, laterEnd) = windows()[1]
        verify(repo, never()).diaryBetween(laterStart, laterEnd)
    }
}
