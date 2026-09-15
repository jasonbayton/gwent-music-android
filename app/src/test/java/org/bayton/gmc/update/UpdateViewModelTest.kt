package org.bayton.gmc.update

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateViewModelTest {
    private val app = RuntimeEnvironment.getApplication()
    private val installed = ApkIdentity(app.packageName, 2, "1.2", setOf("test"))
    private val manifest = """{"versionCode":3,"versionName":"1.3","apkUrl":"https://cdn.bayton.org/download/gmc/a.apk","sha256":"${"a".repeat(64)}"}"""

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Condition did not complete within five seconds", condition())
    }

    private fun readyUpdate(validations: AtomicInteger): UpdateViewModel {
        val bytes = "permission test APK".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val repository = UpdateRepository(OkHttpClient.Builder().addInterceptor {
            val body = if (it.request().url.encodedPath.endsWith("latest.json"))
                manifest.replace("a".repeat(64), hash).toByteArray() else bytes
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(body.toResponseBody()).build()
        }.build())
        return UpdateViewModel(app, repository, installed) { _, _ -> validations.incrementAndGet() }.also {
            it.check()
            awaitCondition { it.state.value.phase == UpdatePhase.AVAILABLE }
            it.download()
            awaitCondition { it.state.value.phase == UpdatePhase.READY }
        }
    }

    @Test fun `permission grant revalidates and hands off only once`() {
        val validations = AtomicInteger()
        val vm = readyUpdate(validations)
        val launches = AtomicInteger()
        try {
            vm.installPermissionNeeded()
            vm.installPermissionReturned(true) { launches.incrementAndGet() }
            awaitCondition { launches.get() == 1 }
            assertEquals(2, validations.get())
            vm.installPermissionReturned(true) { launches.incrementAndGet() }
            assertEquals(1, launches.get())
        } finally { vm.cancel() }
    }

    @Test fun `denied or unsolicited permission return never launches installer`() {
        val vm = readyUpdate(AtomicInteger())
        try {
            vm.installPermissionReturned(true) { fail("Unsolicited return") }
            vm.installPermissionNeeded()
            vm.installPermissionReturned(false) { fail("Permission denied") }
            assertEquals(UpdatePhase.READY, vm.state.value.phase)
            assertTrue(vm.state.value.message!!.contains("not granted"))
            vm.installPermissionReturned(true) { fail("Already consumed return") }
        } finally { vm.cancel() }
    }

    @Test fun `changed APK is rejected after permission grant`() {
        val vm = readyUpdate(AtomicInteger())
        try {
            vm.installPermissionNeeded()
            vm.state.value.apk!!.writeText("changed")
            vm.installPermissionReturned(true) { fail("Changed APK must not launch") }
            awaitCondition { vm.state.value.phase == UpdatePhase.ERROR }
        } finally { vm.cancel() }
    }

    @Test fun `cancel clears pending permission handoff`() {
        val vm = readyUpdate(AtomicInteger())
        vm.installPermissionNeeded()
        vm.cancel()
        vm.installPermissionReturned(true) { fail("Cancelled installation") }
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
    }

    @Test fun `permission handoff survives retained store but not a fresh process`() {
        val vm = readyUpdate(AtomicInteger())
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        fun owner() = object : ViewModelStoreOwner { override val viewModelStore = store }
        val launches = AtomicInteger()
        try {
            val before = ViewModelProvider(owner(), factory)[UpdateViewModel::class.java]
            before.installPermissionNeeded()
            val after = ViewModelProvider(owner(), factory)[UpdateViewModel::class.java]
            assertSame(before, after)
            after.installPermissionReturned(true) { launches.incrementAndGet() }
            awaitCondition { launches.get() == 1 }
            // A fresh process has no verified download to resume. It must not launch from stale intent data.
            val fresh = UpdateViewModel(app)
            fresh.installPermissionReturned(true) { fail("Fresh process must not launch") }
            assertEquals(UpdatePhase.IDLE, fresh.state.value.phase)
            fresh.cancel()
        } finally { vm.cancel(); store.clear() }
    }

    @Test fun `missing APK after permission screen is a recoverable error`() {
        val vm = readyUpdate(AtomicInteger())
        try {
            vm.installPermissionNeeded()
            assertTrue(vm.state.value.apk!!.delete())
            vm.installPermissionReturned(true) { fail("Missing APK must not launch") }
            awaitCondition { vm.state.value.phase == UpdatePhase.ERROR }
            assertNull(vm.state.value.apk)
        } finally { vm.cancel() }
    }

    @Test fun `cancel then restart ignores late response from cancelled check`() {
        val firstEntered = CountDownLatch(1)
        val unblockFirst = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            val number = calls.incrementAndGet()
            if (number == 1) {
                firstEntered.countDown()
                check(unblockFirst.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body((if (number == 1) manifest else manifest.replace("\"versionCode\":3", "\"versionCode\":4")).toResponseBody()).build()
        }.build()
        val vm = UpdateViewModel(app, UpdateRepository(client), installed)
        try {
            vm.check()
            awaitCondition { firstEntered.count == 0L }
            vm.cancel()
            assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
            vm.check()
            awaitCondition { vm.state.value.phase == UpdatePhase.AVAILABLE }
            unblockFirst.countDown()
            awaitCondition { client.dispatcher.runningCallsCount() == 0 }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
            assertEquals(4L, vm.state.value.release?.versionCode)
            assertEquals(2, calls.get())
        } finally { unblockFirst.countDown(); vm.cancel() }
    }

    @Test fun `retained store preserves ongoing check while new store starts idle`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val repository = UpdateRepository(OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(manifest.toResponseBody()).build()
        }.build())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(app, repository, installed) as T
        }
        val store = ViewModelStore()
        val freshStore = ViewModelStore()
        fun owner(value: ViewModelStore) = object : ViewModelStoreOwner { override val viewModelStore = value }
        try {
            val before = ViewModelProvider(owner(store), factory)[UpdateViewModel::class.java]
            before.check()
            awaitCondition { entered.count == 0L }
            // Configuration changes retain the store; process recreation creates a new one.
            val after = ViewModelProvider(owner(store), factory)[UpdateViewModel::class.java]
            assertSame(before, after)
            assertEquals(UpdatePhase.CHECKING, after.state.value.phase)
            val fresh = ViewModelProvider(owner(freshStore), factory)[UpdateViewModel::class.java]
            assertEquals(UpdatePhase.IDLE, fresh.state.value.phase)
            unblock.countDown()
            awaitCondition { after.state.value.phase == UpdatePhase.AVAILABLE }
        } finally { unblock.countDown(); store.clear(); freshStore.clear() }
    }

    @Test fun `clearing store cancels pending work without publishing a stale result`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(manifest.toResponseBody()).build()
        }.build()
        val vm = UpdateViewModel(app, UpdateRepository(client), installed)
        val store = ViewModelStore()
        store.put("updater", vm)
        try {
            vm.check()
            awaitCondition { entered.count == 0L }
            store.clear()
            unblock.countDown()
            awaitCondition { client.dispatcher.runningCallsCount() == 0 }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(UpdatePhase.CHECKING, vm.state.value.phase)
        } finally { unblock.countDown(); store.clear() }
    }

    @Test fun `retained store preserves install verification and cleared store prevents handoff`() {
        for (clearDuringVerification in listOf(false, true)) {
            val bytes = "test APK".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val responseManifest = manifest.replace("a".repeat(64), hash)
            val verificationStarted = CountDownLatch(1)
            val releaseVerification = CountDownLatch(1)
            val validationCalls = AtomicInteger()
            val handedOff = AtomicInteger()
            val repository = UpdateRepository(OkHttpClient.Builder().addInterceptor {
                val body = if (it.request().url.encodedPath.endsWith("latest.json")) responseManifest.toByteArray() else bytes
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(body.toResponseBody()).build()
            }.build())
            val vm = UpdateViewModel(app, repository, installed) { _, _ ->
                if (validationCalls.incrementAndGet() == 2) {
                    verificationStarted.countDown()
                    check(releaseVerification.await(5, TimeUnit.SECONDS))
                }
            }
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            }
            fun owner() = object : ViewModelStoreOwner { override val viewModelStore = store }
            try {
                val before = ViewModelProvider(owner(), factory)[UpdateViewModel::class.java]
                before.check()
                awaitCondition { before.state.value.phase == UpdatePhase.AVAILABLE }
                before.download()
                awaitCondition { before.state.value.phase == UpdatePhase.READY }
                before.prepareInstall { handedOff.incrementAndGet() }
                awaitCondition { verificationStarted.count == 0L }
                val after = ViewModelProvider(owner(), factory)[UpdateViewModel::class.java]
                assertSame(before, after)
                assertEquals(UpdatePhase.VERIFYING, after.state.value.phase)
                val runningJobs = before.viewModelScope.coroutineContext.job.children.toList()
                assertTrue(runningJobs.isNotEmpty())
                if (clearDuringVerification) store.clear()
                releaseVerification.countDown()
                if (!clearDuringVerification) {
                    awaitCondition { handedOff.get() == 1 }
                    assertEquals(UpdatePhase.READY, after.state.value.phase)
                } else {
                    // Wait for cancellation to unwind the IO child and main continuation.
                    awaitCondition { runningJobs.all { it.isCompleted } }
                    assertEquals(0, handedOff.get())
                }
            } finally {
                releaseVerification.countDown()
                vm.cancel()
                store.clear()
            }
        }
    }
}
