package org.bayton.gmc.notify

import android.content.Context
import java.security.MessageDigest

/**
 * Associates notification preferences, seen messages and diary snapshots with one account.
 *
 * The digest is an equality key, not anonymisation or proof of authentication. Keeping it outside
 * credential storage allows the same parent to reauthenticate without losing notification choices.
 * A different account, or an installation without an owner marker, starts with cleared state.
 * Session coordinates activation with worker publication; this class supplies no independent lock.
 */
class NotificationAccountState(private val context: Context) {
    private val prefs = context.getSharedPreferences("gmc_notification_owner", Context.MODE_PRIVATE)

    val owner: String? get() = prefs.getString("owner", null)

    /** Called inside Session's publication lock, before the new account becomes visible. */
    fun activate(contactId: String): String {
        val next = MessageDigest.getInstance("SHA-256").digest(contactId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (owner != next) {
            clear()
            // Write ownership last: interrupted cleanup is repeated on the next activation.
            check(prefs.edit().putString("owner", next).commit())
        }
        return next
    }

    /**
     * Clear dependent stores before the owner marker. Checked synchronous commits deliberately
     * fail activation on a storage error; accepting a new owner after partial cleanup would make
     * old state appear to belong to it. A later activation can repeat the interrupted cleanup.
     */
    fun clear() {
        Notifier.cancelAll(context)
        check(SeenStore(context).clear())
        check(ScheduleSnapshotStore(context).clear())
        check(NotificationPrefs(context).clear())
        check(prefs.edit().clear().commit())
    }
}
