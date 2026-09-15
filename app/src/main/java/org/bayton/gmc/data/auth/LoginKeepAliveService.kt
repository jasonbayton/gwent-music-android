package org.bayton.gmc.data.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import org.bayton.gmc.R

/**
 * Keeps our process out of the cached-app freezer for the duration of a sign-in. Loopback OAuth
 * needs the local server to accept the browser's redirect, but Android freezes a backgrounded
 * process (we sit behind the Custom Tab), which stalls the accept. A short foreground service
 * exempts us until the redirect is captured.
 */
class LoginKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("gmc_login", "Sign-in", NotificationManager.IMPORTANCE_LOW)
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val n = NotificationCompat.Builder(this, "gmc_login")
            .setSmallIcon(R.drawable.gmc_logo)
            .setContentTitle("Signing in to Gwent Music")
            .setContentText("Complete sign-in in your browser...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(this, 42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}
