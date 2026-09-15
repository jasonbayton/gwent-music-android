package org.bayton.gmc.data.api

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bayton.gmc.BuildConfig

/**
 * The customer ApiKey is a shared, organisation-wide credential. It must never be hardcoded
 * in the APK or committed. It is provisioned at runtime and held in Keystore-backed encrypted
 * preferences.
 *
 * Order of resolution:
 *   1) encrypted store (set once, e.g. from a first-party config endpoint or user entry)
 *   2) BuildConfig.DEV_API_KEY (local dev only, from local.properties GWENT_API_KEY=...)
 */
@Suppress("DEPRECATION") // see auth/TokenStore: Jetpack Security deprecation retained deliberately
class ApiKeyProvider(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gwent_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        ?: BuildConfig.DEV_API_KEY.takeIf { it.isNotBlank() }

    fun set(value: String) = prefs.edit().putString(KEY, value).apply()
    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object { const val KEY = "paritor_api_key" }
}
