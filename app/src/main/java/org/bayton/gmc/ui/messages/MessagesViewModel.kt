package org.bayton.gmc.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.cache.Connectivity
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.data.cache.FreshnessClassifier
import org.bayton.gmc.data.cache.SnapshotStore
import org.bayton.gmc.data.model.SecureMessage
import javax.inject.Inject

data class MessagesUi(
    val sent: Boolean = false,
    val items: List<SecureMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val freshness: Freshness = Freshness.SYNCING,
    val syncedAt: Long? = null,
)

/** The persisted received inbox (FR-D01); the Sent tab is not cached (secondary, loaded live). */
@Serializable
data class MessagesSnapshot(val received: List<SecureMessage> = emptyList())

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repo: GwentRepository,
    private val snapshots: SnapshotStore,
    private val connectivity: Connectivity,
) : ViewModel() {
    private val _ui = MutableStateFlow(MessagesUi())
    val ui = _ui.asStateFlow()

    private var job: Job? = null
    init { load(false) }

    fun select(sent: Boolean) { if (sent != _ui.value.sent) load(sent) }

    /** Optimistically show a message as read once it has been opened. */
    fun markRead(itemId: String) {
        _ui.value = _ui.value.copy(items = _ui.value.items.map {
            if (it.contactActivityItem == itemId && it.viewedDate.isNullOrBlank()) it.copy(viewedDate = "read") else it
        })
    }

    /** Cache-first for the received inbox (FR-D01): show saved messages, refresh, keep them on failure. */
    fun load(sent: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            val cached = if (!sent) snapshots.read(SnapshotStore.MESSAGES, MessagesSnapshot.serializer()) else null
            _ui.value = if (cached != null)
                MessagesUi(sent = sent, items = cached.value.received, loading = false, freshness = Freshness.SYNCING, syncedAt = cached.syncedAt)
            else MessagesUi(sent = sent, loading = true, freshness = Freshness.SYNCING)

            val result = try {
                Result.success(repo.messages(sent))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { items ->
                _ui.value = MessagesUi(sent = sent, items = items, loading = false, freshness = Freshness.CURRENT, syncedAt = System.currentTimeMillis())
                if (!sent) snapshots.write(SnapshotStore.MESSAGES, MessagesSnapshot(items), MessagesSnapshot.serializer())
            }.onFailure { e ->
                if (cached != null) _ui.update { it.copy(loading = false, freshness = FreshnessClassifier.classify(e, connectivity.isOnline())) }
                else _ui.value = MessagesUi(sent = sent, loading = false, error = "Couldn't load your messages")
            }
        }
    }
}
