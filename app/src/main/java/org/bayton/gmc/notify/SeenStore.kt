package org.bayton.gmc.notify

import android.content.Context
import java.security.MessageDigest

/**
 * Remembers which messages have already been alerted, so the worker never re-notifies for
 * one it reported before. Stores stable fingerprints (not content). Prunes to a cap.
 */
class SeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("gmc_seen", Context.MODE_PRIVATE)

    val isInitialised: Boolean get() = prefs.getBoolean("init", false)

    fun seen(): Set<String> = prefs.getStringSet("fps", emptySet()) ?: emptySet()

    /** Atomically observe and queue new fingerprints; quiet hours affect delivery, not observation. */
    fun observe(current: Set<String>, enabled: Boolean): Set<String> {
        // Pending is NOT pruned against `current`: `current` is only a limited inbox page, so absence
        // from it does not mean a message was read or deleted. A still-undelivered alert (deferred by
        // quiet hours or a blocked permission) must survive even if it scrolls off that page.
        val pending = if (enabled) pending() + (if (isInitialised) (current - seen()) else emptySet()) else emptySet()
        prefs.edit().putStringSet("fps", current.toList().takeLast(500).toSet())
            .putStringSet("pending", pending).putBoolean("init", true).commit()
        return pending
    }

    fun pending(): Set<String> = prefs.getStringSet("pending", emptySet())?.toSet().orEmpty()
    fun delivered(items: Set<String>) = prefs.edit().putStringSet("pending", pending() - items).commit()

    /** Record the current set as the new baseline (marks initialised on first run). */
    fun commit(current: Set<String>) {
        val capped = current.toList().takeLast(500).toSet()
        prefs.edit().putStringSet("fps", capped).putBoolean("init", true).commit()
    }

    /** Forget all remembered message fingerprints (used on disconnect). */
    fun clear() = prefs.edit().clear().commit()

    companion object {
        fun fingerprint(subject: String?, dateStamp: String?, from: String?, first: String?, last: String?): String {
            val raw = listOf(subject, dateStamp, from, first, last).joinToString("|") { it?.trim().orEmpty() }
            val d = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return d.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
