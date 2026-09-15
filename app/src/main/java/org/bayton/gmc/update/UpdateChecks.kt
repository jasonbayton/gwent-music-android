package org.bayton.gmc.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bayton.gmc.R

internal fun checkIsDue(now: Long, lastAttempt: Long): Boolean =
    lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= 86_400_000L

/** No worker or service: an app-open check, throttled to one attempt per day. */
object UpdateChecks {
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION = 1201
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private fun preferences(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    fun onAppOpen(context: Context) {
        val app = context.applicationContext
        scope.launch { checkOnOpen(app) }
    }

    /** Same app-open path as production, with injectable transport and time for deterministic tests. */
    internal suspend fun checkOnOpen(app: Context, repository: UpdateRepository = UpdateRepository(),
        now: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val prefs = preferences(app)
            if (!enabled(app) || !checkIsDue(now, prefs.getLong("last_attempt", 0))) return@withLock
            prefs.edit().putLong("last_attempt", now).apply()
            try {
                val release = repository.check()
                withContext(Dispatchers.Main) { notifyAvailable(app, release, installedIdentity(app).versionCode) }
            } catch (_: Exception) {
                // Automatic failures are intentionally quiet. Manual checks show actionable errors.
            }
        }
    }

    /** Called on Main, serialising opt-out with the final check and post. */
    internal fun notifyAvailable(app: Context, release: UpdateManifest, installedVersion: Long) {
        val prefs = preferences(app)
        val manager = NotificationManagerCompat.from(app)
        if (!release.isNewerThan(installedVersion)) {
            manager.cancel(NOTIFICATION)
            return
        }
        if (!enabled(app) || prefs.getLong("notified_version", 0) == release.versionCode ||
            !manager.areNotificationsEnabled()) return
        val platform = app.getSystemService(NotificationManager::class.java)
        platform.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "New app versions available to download"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        if (platform.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(app, NOTIFICATION, Intent(app, UpdateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.gmc_logo)
            .setContentTitle("Gwent Music ${release.versionName} is available")
            .setContentText("Tap to review and download the update.")
            .setContentIntent(open).setAutoCancel(true).setSilent(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(NOTIFICATION, notification)
        prefs.edit().putLong("notified_version", release.versionCode).apply()
    }
}
