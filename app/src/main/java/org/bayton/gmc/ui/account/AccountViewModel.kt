package org.bayton.gmc.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.api.ApiKeyProvider
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.data.cache.Connectivity
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.data.cache.FreshnessClassifier
import org.bayton.gmc.data.cache.SnapshotStore
import org.bayton.gmc.di.AppScope
import org.bayton.gmc.notify.NotificationScheduler
import org.bayton.gmc.notify.Notifier
import javax.inject.Inject

data class AccountUi(
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val address: List<String> = emptyList(),
    val pupils: List<String> = emptyList(),
    val accountNo: String? = null,
    val loading: Boolean = true,
    val freshness: Freshness = Freshness.SYNCING,
    val syncedAt: Long? = null,
)

/** The persisted account details (FR-D01). */
@Serializable
data class AccountSnapshot(
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val address: List<String> = emptyList(),
    val pupils: List<String> = emptyList(),
    val accountNo: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repo: GwentRepository,
    private val session: Session,
    private val tokens: TokenStore,
    private val apiKeys: ApiKeyProvider,
    private val snapshots: SnapshotStore,
    private val connectivity: Connectivity,
    @param:AppScope private val appScope: CoroutineScope,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _ui = MutableStateFlow(AccountUi(email = session.email.orEmpty()))
    val ui = _ui.asStateFlow()
    // Latest-request guard: overlapping load()s (e.g. two Retry taps before recomposition hides the
    // control) complete in arbitrary order, so a slower OLDER refresh must not publish over, or persist
    // beneath, a newer one. Each load claims the newest generation; a superseded load stops publishing.
    private var loadGeneration = 0
    init { load() }

    /** Cache-first (FR-D01): show saved account details, refresh, keep them with a freshness on failure. */
    fun load() = viewModelScope.launch {
        val gen = ++loadGeneration
        val cached = snapshots.read(SnapshotStore.ACCOUNT, AccountSnapshot.serializer())
        if (gen != loadGeneration) return@launch          // a newer load started while we read the cache
        _ui.value = cached?.value?.toUi(loading = false, freshness = Freshness.SYNCING, syncedAt = cached.syncedAt)
            ?: AccountUi(email = session.email.orEmpty(), loading = true, freshness = Freshness.SYNCING)

        val result = try {
            Result.success(repo.account())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != loadGeneration) return@launch          // superseded while the account request was in flight
        result.onSuccess { c ->
            // Pupils and the debtor are separate calls; a failure in either must NOT overwrite the
            // cached values with empties, nor let the screen claim CURRENT. Fall back to the cached
            // list/number for the failed part and report the service freshness instead.
            val pupilsRes = attempt { repo.pupils() }
            val debtorRes = attempt { repo.debtor() }
            if (gen != loadGeneration) return@onSuccess   // superseded while the pupils/debtor calls were in flight
            val subError = pupilsRes.exceptionOrNull() ?: debtorRes.exceptionOrNull()
            val pupils = pupilsRes.getOrNull()
                ?.mapNotNull { p -> listOfNotNull(p.firstName?.trim(), p.lastName?.trim()).joinToString(" ").ifBlank { null } }
                ?: cached?.value?.pupils ?: emptyList()
            val accountNo = if (debtorRes.isSuccess) debtorRes.getOrNull()?.accountNo?.trim()?.ifBlank { null }
                else cached?.value?.accountNo
            val snap = AccountSnapshot(
                name = listOfNotNull(c?.firstName?.trim(), c?.lastName?.trim()).joinToString(" ")
                    .ifBlank { c?.contactName?.trim().orEmpty() }.ifBlank { "Your account" },
                email = (c?.email?.trim()?.ifBlank { null }) ?: session.email.orEmpty(),
                phone = (c?.mobile1 ?: c?.landLine1)?.trim()?.ifBlank { null },
                address = listOfNotNull(c?.addressLine1, c?.addressLine2, c?.town, c?.county, c?.postCode)
                    .map { it.trim() }.filter { it.isNotBlank() },
                pupils = pupils,
                accountNo = accountNo,
            )
            if (subError == null) {
                // Everything refreshed: persist and mark current.
                snapshots.write(SnapshotStore.ACCOUNT, snap, AccountSnapshot.serializer())
                if (gen != loadGeneration) return@onSuccess   // superseded while persisting: don't publish a stale CURRENT
                _ui.value = snap.toUi(loading = false, freshness = Freshness.CURRENT, syncedAt = System.currentTimeMillis())
            } else {
                // Fresh contact details, cached pupils/number: show them but keep the saved snapshot and
                // its freshness state rather than marking an incomplete result as current.
                _ui.value = snap.toUi(loading = false, freshness = FreshnessClassifier.classify(subError, connectivity.isOnline()), syncedAt = cached?.syncedAt)
            }
        }.onFailure { e ->
            val freshness = FreshnessClassifier.classify(e, connectivity.isOnline())
            if (cached != null) _ui.update { it.copy(loading = false, freshness = freshness) }
            else _ui.value = AccountUi(email = session.email.orEmpty(), loading = false, freshness = freshness)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Disconnect: remove every account-scoped store, the device key, notification state and scheduled work. */
    fun signOut() {
        // Complete account cleanup before another login can publish its session.
        tokens.clear()                                // invalidate in-flight refresh before navigation
        session.disconnect()
        NotificationScheduler.cancelAll(appContext)   // stop future background checks
        Notifier.cancelAll(appContext)                // clear any posted notification and its badge
        repo.clearCaches()                            // drop in-memory account data
        apiKeys.clear()                               // complete before a later login can install a replacement key
        // Erase persisted screen snapshots on the app scope so the wipe survives this screen's teardown.
        appScope.launch { snapshots.clear() }
    }

    private fun AccountSnapshot.toUi(loading: Boolean, freshness: Freshness, syncedAt: Long?) = AccountUi(
        name = name, email = email, phone = phone, address = address, pupils = pupils,
        accountNo = accountNo, loading = loading, freshness = freshness, syncedAt = syncedAt,
    )
}
