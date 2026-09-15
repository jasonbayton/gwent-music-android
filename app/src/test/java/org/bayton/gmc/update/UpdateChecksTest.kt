package org.bayton.gmc.update

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateChecksTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val release = UpdateManifest(3, "1.3", "https://cdn.bayton.org/download/gmc/a.apk", "a".repeat(64))

    @Before fun reset() {
        app.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun `new release posts a silent low priority notification only once`() {
        UpdateChecks.notifyAvailable(app, release, 2)
        val channel = manager.getNotificationChannel("app_updates")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        UpdateChecks.notifyAvailable(app, release, 2)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `disabling checks removes notification and prevents another post`() {
        UpdateChecks.notifyAvailable(app, release, 2)
        UpdateChecks.setEnabled(app, false)
        UpdateChecks.notifyAvailable(app, release.copy(versionCode = 4), 2)
        assertFalse(UpdateChecks.enabled(app))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `current installation clears stale update notification`() {
        UpdateChecks.notifyAvailable(app, release, 2)
        UpdateChecks.notifyAvailable(app, release, 3)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `permission denial does not post or consume release notification`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateChecks.notifyAvailable(app, release, 2)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0L, app.getSharedPreferences("app_updates", Context.MODE_PRIVATE).getLong("notified_version", 0))
    }

    @Test fun `app open path checks posts and throttles repeat attempts including failures`() {
        val calls = AtomicInteger()
        val repository = UpdateRepository(OkHttpClient.Builder().addInterceptor {
            val number = calls.incrementAndGet()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                .code(if (number == 1) 200 else 503).message("test")
                .body("""{"versionCode":100,"versionName":"test","apkUrl":"https://cdn.bayton.org/download/gmc/a.apk","sha256":"${"a".repeat(64)}"}""".toResponseBody()).build()
        }.build())
        fun openAt(now: Long) {
            val task = CoroutineScope(Dispatchers.Default).async { UpdateChecks.checkOnOpen(app, repository, now) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(task.isCompleted)
            runBlocking { task.await() }
        }
        openAt(100)
        assertEquals(1, calls.get())
        assertEquals(1, manager.activeNotifications.size)
        openAt(101)
        assertEquals(1, calls.get())
        openAt(86_400_100)
        assertEquals(2, calls.get())
        openAt(86_400_101)
        assertEquals(2, calls.get())
        UpdateChecks.setEnabled(app, false)
        openAt(172_800_100)
        assertEquals(2, calls.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
