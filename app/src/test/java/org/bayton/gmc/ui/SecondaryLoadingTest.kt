package org.bayton.gmc.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.api.ApiKeyProvider
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.data.cache.*
import org.bayton.gmc.data.model.*
import org.bayton.gmc.ui.account.*
import org.bayton.gmc.ui.billing.BillingViewModel
import org.bayton.gmc.ui.pupils.PupilsViewModel
import org.bayton.gmc.ui.pupils.PupilActivitiesViewModel
import org.bayton.gmc.ui.pupils.PupilActivity
import org.bayton.gmc.ui.notifications.NotificationSettingsViewModel
import org.bayton.gmc.notify.NotificationPrefs
import org.bayton.gmc.ui.reports.ReportsViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecondaryLoadingTest {
    private val repo = mock(GwentRepository::class.java)
    private val snapshots = mock(SnapshotStore::class.java)
    private val connectivity = mock(Connectivity::class.java)
    private val models = mutableListOf<ViewModel>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun <T : ViewModel> track(vm: T) = vm.also { models += it; drain() }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; appScope.cancel(); drain() }
    private fun account() = track(AccountViewModel(repo, mock(Session::class.java), mock(TokenStore::class.java),
        mock(ApiKeyProvider::class.java), snapshots, connectivity, appScope, RuntimeEnvironment.getApplication()))

    @Test fun `account secondary failure preserves cached pupils and refuses partial cache write`() = runBlocking<Unit> {
        val cached = AccountSnapshot(name = "Old", pupils = listOf("Child"), accountNo = "old-number")
        `when`(snapshots.read(SnapshotStore.ACCOUNT, AccountSnapshot.serializer())).thenReturn(Cached(cached, 123L))
        `when`(connectivity.isOnline()).thenReturn(true)
        `when`(repo.account()).thenReturn(Contact(firstName = "New", email = "parent@example.test"))
        `when`(repo.pupils()).thenAnswer { throw SocketTimeoutException() }
        `when`(repo.debtor()).thenReturn(Debtor(accountNo = "fresh-number"))
        val vm = account()
        assertEquals("New", vm.ui.value.name)
        assertEquals(listOf("Child"), vm.ui.value.pupils)
        assertEquals("fresh-number", vm.ui.value.accountNo)
        assertEquals(Freshness.SERVICE_HOLD, vm.ui.value.freshness)
        assertEquals(123L, vm.ui.value.syncedAt)
        assertFalse(mockingDetails(snapshots).invocations.any { it.method.name == "write" })
    }

    @Test fun `account top level failure retains cached details offline`() = runBlocking<Unit> {
        `when`(snapshots.read(SnapshotStore.ACCOUNT, AccountSnapshot.serializer()))
            .thenReturn(Cached(AccountSnapshot(name = "Saved"), 123L))
        `when`(repo.account()).thenAnswer { throw SocketTimeoutException() }
        val vm = account()
        assertEquals("Saved", vm.ui.value.name)
        assertEquals(Freshness.OFFLINE, vm.ui.value.freshness)
        assertFalse(vm.ui.value.loading)
        verify(repo, never()).pupils()
        verify(repo, never()).debtor()
    }

    @Test fun `complete account refresh trims fields and caches result`() = runBlocking<Unit> {
        `when`(repo.account()).thenReturn(Contact(firstName = " Pat ", lastName = " Example ", email = " p@example.test "))
        `when`(repo.pupils()).thenReturn(emptyList())
        `when`(repo.debtor()).thenReturn(null)
        val vm = account()
        assertEquals("Pat Example", vm.ui.value.name)
        assertEquals("p@example.test", vm.ui.value.email)
        assertEquals(Freshness.CURRENT, vm.ui.value.freshness)
        assertEquals(1, mockingDetails(snapshots).invocations.count { it.method.name == "write" })
    }

    @Test fun `pupils retry replaces failure with successful empty response`() = runBlocking<Unit> {
        `when`(repo.pupils()).thenAnswer { throw SocketTimeoutException("timeout") }.thenReturn(emptyList())
        val vm = track(PupilsViewModel(repo))
        assertNotNull(vm.ui.value.error)
        vm.load(); drain()
        assertNull(vm.ui.value.error)
        assertTrue(vm.ui.value.items.isEmpty())
        assertFalse(vm.ui.value.loading)
    }

    @Test fun `reports retry replaces failure with successful empty response`() = runBlocking<Unit> {
        `when`(repo.reports()).thenAnswer { throw SocketTimeoutException("timeout") }.thenReturn(emptyList())
        val vm = track(ReportsViewModel(repo))
        assertNotNull(vm.ui.value.error)
        vm.load(); drain()
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
    }

    @Test fun `billing prefers wallet balance and handles no data`() = runBlocking<Unit> {
        `when`(repo.debtor()).thenReturn(Debtor(outstandingBalance = 25.0))
        `when`(repo.wallet()).thenReturn(listOf(WalletItem(outstandingDebt = 10.0)))
        val vm = track(BillingViewModel(repo))
        assertEquals(10.0, vm.ui.value.balance)
        assertFalse(vm.ui.value.isEmpty)
        `when`(repo.debtor()).thenReturn(null)
        `when`(repo.wallet()).thenReturn(emptyList())
        vm.load(); drain()
        assertTrue(vm.ui.value.isEmpty)
        assertNull(vm.ui.value.balance)
    }

    @Test fun `pupil activities separate live applications and order history newest first`() = runBlocking<Unit> {
        fun activity(id: String, status: String, accepted: Boolean, year: Int) =
            PupilActivity(id, "", false, "Brass", "Venue", null, status, accepted, null, year, false)
        val pending = activity("pending", "Offered", false, 2026)
        val accepted = activity("accepted", "Offered", true, 2026)
        val old = activity("old", "Closed", false, 2024)
        `when`(repo.pupilActivities("pupil")).thenReturn(listOf(old, pending, accepted))
        val vm = track(PupilActivitiesViewModel(repo, SavedStateHandle(mapOf("pupilId" to "pupil", "name" to " Child "))))
        assertEquals("Child", vm.pupilName)
        assertEquals(listOf(pending), vm.ui.value.applications)
        assertEquals(listOf(2026, 2024), vm.ui.value.years)
        assertEquals(listOf(accepted), vm.ui.value.byYear[2026])
    }

    @Test fun `notification settings seed saved preferences and distinct nonblank pupil names`() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val prefs = NotificationPrefs(app)
        prefs.clear()
        prefs.messages = false
        prefs.setPupilEnabled("Child", false)
        `when`(repo.pupils()).thenReturn(listOf(RelatedPupil(firstName = " Child "), RelatedPupil(firstName = "Child"), RelatedPupil(firstName = " ")))
        val vm = track(NotificationSettingsViewModel(repo, app))
        assertFalse(vm.ui.value.messages)
        assertFalse(vm.ui.value.loading)
        assertEquals(listOf("Child"), vm.ui.value.pupils.map { it.name })
        assertFalse(vm.ui.value.pupils.single().enabled)
    }
}
