package org.bayton.gmc.data.cache

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.notify.NotificationAccountState
import org.bayton.gmc.ui.account.AccountSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageGapTest {
    private lateinit var session: Session
    private lateinit var disk: TestStore
    private lateinit var store: SnapshotStore
    private val serializer = AccountSnapshot.serializer()
    private val value = AccountSnapshot(name = "Account A")

    /** Sequential storage fixture with delayed/erroring reads; not a test of DataStore's own atomicity. */
    private class TestStore : DataStore<Preferences> {
        var prefs: Preferences = emptyPreferences()
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        override val data: Flow<Preferences> get() = flow {
            readGate?.await()
            readFailure?.let { throw it }
            emit(prefs)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeGate?.await()
            writeFailure?.let { throw it }
            return transform(prefs).also { prefs = it }
        }
    }

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        session = Session(app.getSharedPreferences("gap_session", Context.MODE_PRIVATE), NotificationAccountState(app))
        session.activate("account-A", "a@example.test")
        disk = TestStore()
        store = SnapshotStore(disk, session)
    }

    private fun key(suffix: String) = "${session.snapshot()!!.owner}/${SnapshotStore.ACCOUNT}/$suffix"

    @Test fun `unchanged owner receives a delayed valid snapshot`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        val gate = CompletableDeferred<Unit>()
        disk.readGate = gate
        val read = async(start = CoroutineStart.UNDISPATCHED) { store.read(SnapshotStore.ACCOUNT, serializer) }
        gate.complete(Unit)
        assertEquals(value, read.await()?.value)
    }

    @Test fun `new owner cannot read the old owner's stored snapshot`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        session.disconnect()
        session.activate("account-B", "b@example.test")
        assertNull(store.read(SnapshotStore.ACCOUNT, serializer))
    }

    @Test fun `unknown JSON field does not invalidate compatible snapshot`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.prefs = disk.prefs.toMutablePreferences().apply {
            this[stringPreferencesKey(key("json"))] = """{"name":"Account A","futureField":true}"""
        }
        assertEquals(value, store.read(SnapshotStore.ACCOUNT, serializer)?.value)
    }

    @Test fun `malformed JSON is a cache miss`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.prefs = disk.prefs.toMutablePreferences().apply { this[stringPreferencesKey(key("json"))] = "{broken" }
        assertNull(store.read(SnapshotStore.ACCOUNT, serializer))
    }

    @Test fun `incompatible schema is a cache miss`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.prefs = disk.prefs.toMutablePreferences().apply { this[intPreferencesKey(key("ver"))] = 999 }
        assertNull(store.read(SnapshotStore.ACCOUNT, serializer))
    }

    @Test fun `missing timestamp is a cache miss`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.prefs = disk.prefs.toMutablePreferences().apply { remove(longPreferencesKey(key("at"))) }
        assertNull(store.read(SnapshotStore.ACCOUNT, serializer))
    }

    @Test fun `unavailable disk read is a cache miss`() = runBlocking<Unit> {
        disk.readFailure = IOException("Synthetic unavailable disk")
        assertNull(store.read(SnapshotStore.ACCOUNT, serializer))
    }

    @Test fun `failed atomic disk write retains the previous snapshot`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.writeFailure = IOException("Synthetic full disk")
        store.write(SnapshotStore.ACCOUNT, AccountSnapshot(name = "Replacement"), serializer)
        assertEquals(value, store.read(SnapshotStore.ACCOUNT, serializer)?.value)
    }

    @Test fun `read suspended across account change must not return previous owner data`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        val gate = CompletableDeferred<Unit>()
        disk.readGate = gate
        val read = async(start = CoroutineStart.UNDISPATCHED) { store.read(SnapshotStore.ACCOUNT, serializer) }
        session.disconnect()
        session.activate("account-B", "b@example.test")
        gate.complete(Unit)
        assertNull("Old-owner data must not escape after the session changed", read.await())
    }

    @Test fun `cancelled read propagates cancellation instead of becoming a cache miss`() = runBlocking<Unit> {
        disk.readFailure = CancellationException("Synthetic cancelled IO")
        var cancellation: CancellationException? = null
        try { store.read(SnapshotStore.ACCOUNT, serializer) } catch (e: CancellationException) { cancellation = e }
        assertNotNull("Cancellation must not be swallowed", cancellation)
    }

    @Test fun `cancelling suspended read must prevent downstream caller publication`() = runBlocking<Unit> {
        disk.readGate = CompletableDeferred()
        var published = false
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            store.read(SnapshotStore.ACCOUNT, serializer)
            published = true
        }
        read.cancelAndJoin()
        assertTrue(read.isCancelled)
        assertFalse("Code after the cancelled suspension must not run", published)
    }

    // A cancelled write or clear must propagate the cancellation, not report success.
    @Test fun `cancelled write propagates cancellation instead of silently succeeding`() = runBlocking<Unit> {
        disk.writeFailure = CancellationException("Synthetic cancelled write")
        var cancellation: CancellationException? = null
        try { store.write(SnapshotStore.ACCOUNT, value, serializer) } catch (e: CancellationException) { cancellation = e }
        assertNotNull("Cancellation must not be swallowed by write", cancellation)
    }

    @Test fun `cancelling a suspended write must prevent downstream caller publication`() = runBlocking<Unit> {
        disk.writeGate = CompletableDeferred()
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            store.write(SnapshotStore.ACCOUNT, value, serializer)
            published = true
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse("Code after the cancelled write must not run", published)
    }

    @Test fun `cancelled clear propagates cancellation`() = runBlocking<Unit> {
        disk.writeFailure = CancellationException("Synthetic cancelled clear")
        var cancellation: CancellationException? = null
        try { store.clear() } catch (e: CancellationException) { cancellation = e }
        assertNotNull("Cancellation must not be swallowed by clear", cancellation)
    }

    @Test fun `failed clear is non-fatal and leaves stored data intact`() = runBlocking<Unit> {
        store.write(SnapshotStore.ACCOUNT, value, serializer)
        disk.writeFailure = IOException("Synthetic full disk")
        store.clear()                                   // must not throw
        disk.writeFailure = null
        assertEquals(value, store.read(SnapshotStore.ACCOUNT, serializer)?.value)
    }
}
