package org.bayton.gmc.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.bayton.gmc.data.auth.Session
import javax.inject.Inject
import javax.inject.Singleton

private val Context.snapshotDataStore by preferencesDataStore("gmc_snapshots")

/** A cached screen snapshot and when it was fetched. */
data class Cached<T>(val value: T, val syncedAt: Long)

/**
 * Per-screen whole-snapshot cache (FR-D01) on Preferences DataStore: the app writes and reads a
 * screen's snapshot as one serialised blob, never querying or paging, which is why Room was not
 * used. Entries are keyed by account owner so one account can never read another's cache, and are
 * wiped on disconnect. Not encrypted at rest by design (allowBackup=false, app sandbox, and this is
 * lesson/message metadata rather than credentials; credentials live in the encrypted TokenStore).
 * A schema version guards the format so an app update discards an incompatible snapshot instead of
 * crashing on it.
 */
@Singleton
class SnapshotStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val session: Session,
) {
    // Inject the real per-app store in production; the internal constructor lets tests drive a store
    // with delayed/erroring/suspended reads without touching the production wiring.
    @Inject constructor(@ApplicationContext context: Context, session: Session) :
        this(context.snapshotDataStore, session)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private fun jsonKey(owner: String, screen: String) = stringPreferencesKey("$owner/$screen/json")
    private fun atKey(owner: String, screen: String) = longPreferencesKey("$owner/$screen/at")
    private fun verKey(owner: String, screen: String) = intPreferencesKey("$owner/$screen/ver")

    /** The signed-in account's cache key, or null when there is nobody to cache for. */
    private fun owner(): String? = session.snapshot()?.owner

    suspend fun <T> read(screen: String, serializer: KSerializer<T>): Cached<T>? {
        val owner = owner() ?: return null
        val prefs = try {
            dataStore.data.first()
        } catch (cancelled: CancellationException) {
            throw cancelled                       // a cancelled read is cancellation, never a silent cache miss
        } catch (e: Exception) {
            return null                           // unavailable/errored storage degrades to a cache miss
        }
        // The read suspends on disk IO; if the session changed meanwhile (sign-out or account switch),
        // this snapshot belongs to a superseded owner and must not escape to the current screen.
        if (owner() != owner) return null
        if (prefs[verKey(owner, screen)] != SCHEMA) return null            // discard an incompatible format
        val raw = prefs[jsonKey(owner, screen)] ?: return null
        val at = prefs[atKey(owner, screen)] ?: return null
        return runCatching { Cached(json.decodeFromString(serializer, raw), at) }.getOrNull()
    }

    suspend fun <T> write(screen: String, value: T, serializer: KSerializer<T>) {
        val owner = owner() ?: return
        val raw = runCatching { json.encodeToString(serializer, value) }.getOrNull() ?: return
        try {
            dataStore.edit {
                it[jsonKey(owner, screen)] = raw
                it[atKey(owner, screen)] = System.currentTimeMillis()
                it[verKey(owner, screen)] = SCHEMA
            }
        } catch (cancelled: CancellationException) {
            throw cancelled                       // propagate cancellation rather than reporting a phantom success
        } catch (e: Exception) {
            // A failed atomic disk write leaves the previous snapshot intact rather than crashing the caller.
        }
    }

    /** Erase every cached snapshot (used on disconnect, so a new sign-in starts clean). */
    suspend fun clear() {
        try {
            dataStore.edit { it.clear() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // A failed clear is non-fatal; the next sign-in still re-keys by owner.
        }
    }

    companion object {
        private const val SCHEMA = 1
        const val HOME = "home"
        const val SCHEDULE = "schedule"
        const val MESSAGES = "messages"
        const val ACCOUNT = "account"
    }
}
