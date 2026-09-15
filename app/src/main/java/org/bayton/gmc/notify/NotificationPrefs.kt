package org.bayton.gmc.notify

import android.content.Context

/**
 * Notification preferences (not secrets, so plain SharedPreferences). Times are stored as
 * minutes-of-day. Per-pupil control is stored as the set of DISABLED pupil names, so a newly
 * added pupil is on by default.
 */
class NotificationPrefs(context: Context) {
    private val p = context.getSharedPreferences("gmc_notif_prefs", Context.MODE_PRIVATE)

    var messages: Boolean
        get() = p.getBoolean(MESSAGES, true)
        set(v) = p.edit().putBoolean(MESSAGES, v).apply()

    var morning: Boolean
        get() = p.getBoolean(MORNING, true)
        set(v) = p.edit().putBoolean(MORNING, v).apply()
    var morningMinutes: Int
        get() = p.getInt(MORNING_MIN, 7 * 60 + 30)   // 07:30
        set(v) = p.edit().putInt(MORNING_MIN, v.coerceIn(0, 1439)).apply()

    var dayBefore: Boolean
        get() = p.getBoolean(DIGEST, true)
        set(v) = p.edit().putBoolean(DIGEST, v).apply()
    var digestMinutes: Int
        get() = p.getInt(DIGEST_MIN, 18 * 60)        // 18:00
        set(v) = p.edit().putInt(DIGEST_MIN, v.coerceIn(0, 1439)).apply()

    var scheduleChanges: Boolean
        get() = p.getBoolean(CHANGES, true)
        set(v) = p.edit().putBoolean(CHANGES, v).apply()

    var quietStartMinutes: Int
        get() = p.getInt(QUIET_START, 21 * 60)       // 21:00
        set(v) = p.edit().putInt(QUIET_START, v.coerceIn(0, 1439)).apply()
    var quietEndMinutes: Int
        get() = p.getInt(QUIET_END, 7 * 60)          // 07:00
        set(v) = p.edit().putInt(QUIET_END, v.coerceIn(0, 1439)).apply()

    fun disabledPupils(): Set<String> = p.getStringSet(PUPILS_OFF, emptySet()) ?: emptySet()
    fun pupilEnabled(name: String): Boolean = name.trim() !in disabledPupils()
    fun setPupilEnabled(name: String, enabled: Boolean) {
        val set = disabledPupils().toMutableSet()
        if (enabled) set.remove(name.trim()) else set.add(name.trim())
        p.edit().putStringSet(PUPILS_OFF, set).apply()
    }

    /** Per-occurrence idempotency: has this reminder type already fired for this day? */
    fun reminderFired(type: String, dayIso: String): Boolean = p.getString(firedKey(type), null) == dayIso
    fun markReminderFired(type: String, dayIso: String) = p.edit().putString(firedKey(type), dayIso).apply()
    private fun firedKey(type: String) = "fired_$type"

    /** True when a minute-of-day falls inside the quiet-hours window (handles overnight wrap). */
    fun inQuietHours(minuteOfDay: Int): Boolean = QuietHours.contains(minuteOfDay, quietStartMinutes, quietEndMinutes)

    fun clear() = p.edit().clear().commit()

    private companion object {
        const val MESSAGES = "messages"; const val MORNING = "morning"; const val MORNING_MIN = "morning_min"
        const val DIGEST = "digest"; const val DIGEST_MIN = "digest_min"; const val CHANGES = "changes"
        const val QUIET_START = "quiet_start"; const val QUIET_END = "quiet_end"; const val PUPILS_OFF = "pupils_off"
    }
}
