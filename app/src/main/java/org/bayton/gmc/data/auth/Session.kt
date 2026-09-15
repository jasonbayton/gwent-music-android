package org.bayton.gmc.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bayton.gmc.notify.NotificationAccountState

/**
 * Persists the resolved account and arbitrates publication by background work.
 *
 * Notification ownership survives token expiry; [generation] does not survive process death and
 * instead invalidates in-flight work on every local account transition. Workers capture [snapshot]
 * before fetching and publish through [ifCurrent], so a response from an earlier login cannot update
 * the newly active account's notification state. Neither value is an authentication credential.
 *
 * Account transitions and publication share this object's monitor. Keep network calls outside it;
 * the notification workers' coroutine mutex serialises workers, not login or disconnect actions.
 */
@Suppress("DEPRECATION") // androidx.security.crypto is deprecated with no drop-in replacement; retained until a Tink/Keystore migration lands
class Session internal constructor(private val prefs: SharedPreferences, private val notifications: NotificationAccountState) {
    constructor(context: Context) : this(EncryptedSharedPreferences.create(
        context, "gmc_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ), NotificationAccountState(context))
    private var generation = 0L
    var email: String? get() = prefs.getString("email", null); set(v) { prefs.edit().putString("email", v).apply() }
    private val _signedIn = MutableStateFlow(!prefs.getString("cid", null).isNullOrBlank())
    val signedIn = _signedIn.asStateFlow()
    val contactId: String? get() = prefs.getString("cid", null)
    var debtorId: String? get() = prefs.getString("did", null); set(v) { prefs.edit().putString("did", v).apply() }
    val isSignedIn: Boolean get() = !contactId.isNullOrBlank()
    /** Token expiry keeps notification ownership so reauthenticating the same parent preserves state. */
    @Synchronized fun clear() { generation++; prefs.edit().clear().apply(); _signedIn.value = false }

    /** Finish notification-state migration before exposing the account to signed-in collectors. */
    @Synchronized fun activate(contactId: String, email: String?) {
        notifications.activate(contactId)
        generation++
        prefs.edit().putString("cid", contactId).putString("email", email).remove("did").apply()
        _signedIn.value = true
    }

    /** Explicit disconnect forgets account preferences as well as credentials, before another login. */
    @Synchronized fun disconnect() { clear(); notifications.clear() }

    data class Snapshot(val owner: String, val generation: Long)

    @Synchronized fun snapshot(): Snapshot? {
        val cid = contactId?.takeIf { it.isNotBlank() } ?: return null
        // Also migrates an already signed-in installation whose notification owner was not recorded.
        return Snapshot(notifications.activate(cid), generation)
    }

    /** Only short non-suspending publication belongs here; never perform network I/O under this lock. */
    @Synchronized fun <T> ifCurrent(expected: Snapshot, publish: () -> T): T? =
        if (isSignedIn && generation == expected.generation && notifications.owner == expected.owner) publish() else null
}
