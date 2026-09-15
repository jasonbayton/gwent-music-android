package org.bayton.gmc.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.bayton.gmc.MainActivity
import org.bayton.gmc.R

object Notifier {
    private const val CH_MESSAGES = "gmc_messages_v2"     // v2: HIGH importance (heads-up)
    private const val CH_REMINDERS = "gmc_reminders"      // morning + day-before lesson reminders
    private const val CH_CHANGES = "gmc_schedule_changes" // moved/cancelled lessons

    const val ID_MESSAGES = 1001
    const val ID_MORNING = 1002
    const val ID_DIGEST = 1003
    const val ID_CHANGES = 1100

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New Gwent Music messages"; setShowBadge(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Lesson reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Morning and day-before lesson reminders"; setShowBadge(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CHANGES, "Schedule changes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a lesson is moved or cancelled"; setShowBadge(true)
        })
    }

    fun notifyNewMessages(context: Context, count: Int): Boolean {
        if (count <= 0) return false
        // Privacy-safe default: generic text, no sender or subject on the lock screen.
        val text = if (count == 1) "You have a new message" else "You have $count new messages"
        return post(context, CH_MESSAGES, ID_MESSAGES, "Gwent Music", text, "messages", NotificationCompat.PRIORITY_HIGH, number = count)
    }

    fun notifyMorning(context: Context, title: String, text: String) =
        post(context, CH_REMINDERS, ID_MORNING, title, text, "schedule", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyDigest(context: Context, title: String, text: String) =
        post(context, CH_REMINDERS, ID_DIGEST, title, text, "schedule", NotificationCompat.PRIORITY_DEFAULT)

    fun notifyScheduleChange(context: Context, text: String) =
        post(context, CH_CHANGES, ID_CHANGES, "Schedule updated", text, "schedule", NotificationCompat.PRIORITY_HIGH)

    /** Remove every posted Gwent Music notification and its launcher badge (used on disconnect). */
    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()

    private fun post(
        context: Context, channel: String, id: Int, title: String, text: String,
        route: String, priority: Int, number: Int = 0,
    ): Boolean {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("route", route)
        }
        val pi = PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.gmc_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (number > 0) setNumber(number) }
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try { manager.notify(id, n); true } catch (_: SecurityException) { false }
    }
}
