package org.bayton.gmc.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.api.ApiKeyProvider
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.data.cache.*
import org.bayton.gmc.data.model.Contact
import org.bayton.gmc.ui.account.AccountViewModel
import org.bayton.gmc.ui.account.AccountSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** These assertions describe the required result, not the current implementation's behaviour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RefreshGapTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> delayed(value: CompletableDeferred<T>) = Answer<Any?> { call ->
        (suspend { value.await() }).startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<T>)
    }

    /** Registers an any() matcher but returns a non-null stand-in, so stubbing a Kotlin method with
     *  non-null parameters does not trip the compiler's parameter null-check (what mockito-kotlin does). */
    private fun <T> anyMatcher(stub: T): T { any<Any>(); return stub }

    @Test fun `older account refresh cannot replace a completed newer refresh`() = runBlocking<Unit> {
        val repo = mock(GwentRepository::class.java)
        val snapshots = mock(SnapshotStore::class.java)
        val older = CompletableDeferred<Contact?>()
        `when`(repo.account()).thenAnswer { throw java.net.SocketTimeoutException() }
            .thenAnswer(delayed(older)).thenReturn(Contact(firstName = "Newer"))
        `when`(repo.pupils()).thenReturn(emptyList())
        `when`(repo.debtor()).thenReturn(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = AccountViewModel(repo, mock(Session::class.java), mock(TokenStore::class.java),
            mock(ApiKeyProvider::class.java), snapshots, mock(Connectivity::class.java),
            scope, RuntimeEnvironment.getApplication())
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        try {
            drain()
            assertEquals(Freshness.OFFLINE, vm.ui.value.freshness)
            // Retry is now exposed by AccountContent. Two callbacks can arrive before recomposition.
            val retry = vm::load
            retry(); retry(); drain()
            assertEquals("Positive control: newer request completed", "Newer", vm.ui.value.name)
            older.complete(Contact(firstName = "Older")); drain()
            val writes = mockingDetails(snapshots).invocations.filter { it.method.name == "write" }
            assertEquals("Persisted and visible results must both remain the newest", listOf("Newer", "Newer"),
                listOf((writes.last().arguments[1] as AccountSnapshot).name, vm.ui.value.name))
        } finally { vm.viewModelScope.cancel(); scope.cancel(); drain() }
    }

    @Test fun `an older refresh superseded while persisting cannot publish a stale current`() = runBlocking<Unit> {
        val repo = mock(GwentRepository::class.java)
        val snapshots = mock(SnapshotStore::class.java)
        `when`(repo.account()).thenAnswer { throw java.net.SocketTimeoutException() }
            .thenReturn(Contact(firstName = "Older")).thenReturn(Contact(firstName = "Newer"))
        `when`(repo.pupils()).thenReturn(emptyList())
        `when`(repo.debtor()).thenReturn(null)
        // The OLDER load reaches the snapshot write first and suspends there; the NEWER load then
        // completes and publishes. The older continuation must not publish its stale CURRENT afterwards.
        val olderWrite = CompletableDeferred<Unit>()
        `when`(snapshots.write(anyString(), anyMatcher(AccountSnapshot()), anyMatcher(AccountSnapshot.serializer())))
            .thenAnswer(delayed(olderWrite)).thenReturn(Unit)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = AccountViewModel(repo, mock(Session::class.java), mock(TokenStore::class.java),
            mock(ApiKeyProvider::class.java), snapshots, mock(Connectivity::class.java),
            scope, RuntimeEnvironment.getApplication())
        fun drain() = shadowOf(Looper.getMainLooper()).idle()
        try {
            drain()
            assertEquals(Freshness.OFFLINE, vm.ui.value.freshness)
            val retry = vm::load
            retry(); drain()                              // older: resolves, reaches write, suspends there
            retry(); drain()                              // newer: resolves and publishes CURRENT "Newer"
            assertEquals("Positive control: newer request published", "Newer", vm.ui.value.name)
            olderWrite.complete(Unit); drain()            // older write completes; older continuation resumes
            assertEquals("A superseded load must not publish after its write finishes", "Newer", vm.ui.value.name)
        } finally { vm.viewModelScope.cancel(); scope.cancel(); drain() }
    }
}
