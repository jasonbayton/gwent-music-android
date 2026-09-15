package org.bayton.gmc.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keystore-backed encrypted storage for the access + refresh tokens. */
@Suppress("DEPRECATION") // androidx.security.crypto is deprecated with no drop-in replacement; retained until a Tink/Keystore migration lands
class TokenStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(EncryptedSharedPreferences.create(
        context, "gwent_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ))
    // A generation separates logins, including logout during an in-flight refresh.
    private var generation = 0L
    @Synchronized fun snapshot() = Credentials(accessToken, refreshToken, prefs.getLong("expires", 0), generation)
    @Synchronized fun save(result: TokenResult, now: Long = System.currentTimeMillis()) {
        generation++
        write(result, now)
    }
    private fun write(result: TokenResult, now: Long) {
        prefs.edit().putString("at", result.accessToken).putString("rt", result.refreshToken)
            .putLong("expires", result.expiresIn?.takeIf { it in 1..86400 }?.let { now + it * 1000 } ?: 0).apply()
    }
    @Synchronized fun replace(expected: Credentials, result: TokenResult, now: Long): Credentials? {
        if (snapshot() != expected) return null
        write(result.copy(refreshToken = result.refreshToken ?: expected.refresh), now)
        return snapshot()
    }
    @Synchronized fun expire(expected: Credentials): Credentials {
        if (snapshot() == expected) prefs.edit().putLong("expires", 1).apply()
        return snapshot()
    }
    @Synchronized fun invalidate(expected: Credentials, onInvalidated: () -> Unit): Boolean {
        if (snapshot() != expected) return false
        clear()
        onInvalidated()
        return true
    }
    var accessToken: String? get() = prefs.getString("at", null); @Synchronized set(v) { generation++; prefs.edit().putString("at", v).remove("expires").apply() }
    var refreshToken: String? get() = prefs.getString("rt", null); set(v) { prefs.edit().putString("rt", v).apply() }
    @Synchronized fun clear() { generation++; prefs.edit().clear().apply() }
}

data class Credentials(val access: String?, val refresh: String?, val expiresAt: Long, val generation: Long)
