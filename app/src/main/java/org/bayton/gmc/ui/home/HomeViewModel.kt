package org.bayton.gmc.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.cache.Connectivity
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.data.cache.FreshnessClassifier
import org.bayton.gmc.data.cache.SnapshotStore
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: GwentRepository,
    private val snapshots: SnapshotStore,
    private val connectivity: Connectivity,
) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi(greeting = repo.homeGreeting(), dateLine = repo.homeDateLine()))
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()

    private var coordJob: Job? = null
    private var overviewJob: Job? = null       // one job per module, so a targeted retry cancels and
    private var termJob: Job? = null           // reloads only its own module and never a sibling's
    private var messagesJob: Job? = null        // still-in-flight load
    private var seeded = false                 // the shown dashboard came from the cached snapshot
    @Volatile private var liveOverview = false // this module has a fresh (this-session) success
    @Volatile private var liveTerm = false
    @Volatile private var liveMessages = false
    private var overviewError: Throwable? = null
    private var termError: Throwable? = null
    private var messagesError: Throwable? = null

    init { refresh() }

    /**
     * Reload the dashboard, cache-first (FR-D01): render the last saved snapshot immediately, refresh
     * the three modules in parallel (one slow module never blocks the others), then persist a new
     * snapshot only when all three are freshly successful, else keep the cached view with a
     * freshness state (FR-D02).
     */
    fun refresh() {
        cancelAll()
        _ui.update { it.copy(greeting = repo.homeGreeting(), dateLine = repo.homeDateLine()) }
        coordJob = viewModelScope.launch {
            liveOverview = false; liveTerm = false; liveMessages = false
            overviewError = null; termError = null; messagesError = null
            val cached = snapshots.read(SnapshotStore.HOME, HomeSnapshot.serializer())
            seeded = cached != null
            if (cached != null) {
                val s = cached.value
                // Recompute the cached lesson labels against today's clock (a saved "tomorrow" must
                // read "today" when reopened on the day), then show them while the refresh runs.
                _ui.update { it.copy(
                    overview = Load.Ready(repo.relabelOverview(Overview(s.who, s.next, s.nextUp))),
                    term = Load.Ready(TermProgress(s.termTotal, s.termCompleted)),
                    messages = Load.Ready(s.attention),
                    reauthRequired = false, freshness = Freshness.SYNCING, syncedAt = cached.syncedAt,
                ) }
            } else {
                _ui.update { it.copy(overview = Load.Loading, term = Load.Loading, messages = Load.Loading, freshness = Freshness.SYNCING) }
            }
            if (repo.requiresReauth()) {
                _ui.update { it.copy(reauthRequired = true, freshness = Freshness.REAUTH_REQUIRED) }
                return@launch
            }
            _ui.update { it.copy(reauthRequired = false) }

            // Each module runs in its own job (siblings of this coordinator), so a later targeted retry
            // can cancel and replace just one without touching the others.
            overviewJob = viewModelScope.launch { fetchOverview() }
            termJob = viewModelScope.launch { fetchTerm() }
            messagesJob = viewModelScope.launch { fetchMessages() }
            overviewJob?.join(); termJob?.join(); messagesJob?.join()
            finalise()
        }
    }

    // Targeted single-module retry: cancel and reload ONLY this module's job, leaving any in-flight
    // sibling loads running, then re-derive freshness. A full refresh cancels all module jobs first,
    // so a stale retry can never land after a newer refresh.
    fun retryOverview() { overviewJob?.cancel(); overviewJob = viewModelScope.launch { fetchOverview() }; finaliseAfter(overviewJob) }
    fun retryTerm() { termJob?.cancel(); termJob = viewModelScope.launch { fetchTerm() }; finaliseAfter(termJob) }
    fun retryMessages() { messagesJob?.cancel(); messagesJob = viewModelScope.launch { fetchMessages() }; finaliseAfter(messagesJob) }

    private fun cancelAll() { coordJob?.cancel(); overviewJob?.cancel(); termJob?.cancel(); messagesJob?.cancel() }
    private fun finaliseAfter(job: Job?) { viewModelScope.launch { job?.join(); finalise() } }

    private suspend fun fetchOverview() {
        if (!seeded) _ui.update { it.copy(overview = Load.Loading) }
        attempt { repo.homeOverview() }
            .onSuccess { v -> liveOverview = true; overviewError = null; _ui.update { it.copy(overview = Load.Ready(v)) } }
            .onFailure { e -> liveOverview = false; overviewError = e; if (!seeded) _ui.update { it.copy(overview = Load.Failed(e.message ?: "Couldn't load")) } }
    }

    private suspend fun fetchTerm() {
        if (!seeded) _ui.update { it.copy(term = Load.Loading) }
        attempt { repo.termProgress() }
            .onSuccess { v -> liveTerm = true; termError = null; _ui.update { it.copy(term = Load.Ready(v)) } }
            .onFailure { e -> liveTerm = false; termError = e; if (!seeded) _ui.update { it.copy(term = Load.Failed(e.message ?: "Couldn't load")) } }
    }

    private suspend fun fetchMessages() {
        if (!seeded) _ui.update { it.copy(messages = Load.Loading) }
        attempt { repo.homeMessages() }
            .onSuccess { v -> liveMessages = true; messagesError = null; _ui.update { it.copy(messages = Load.Ready(v)) } }
            .onFailure { e -> liveMessages = false; messagesError = e; if (!seeded) _ui.update { it.copy(messages = Load.Failed(e.message ?: "Couldn't load")) } }
    }

    /** Persist a fresh snapshot only when all three modules are freshly successful; otherwise keep what
     *  is shown with its freshness state. */
    private suspend fun finalise() {
        // Keep errors per module: a later timeout must not hide an authentication failure, and a
        // successful targeted retry must remove only that module's error, not its siblings' errors.
        val errors = listOfNotNull(overviewError, termError, messagesError)
        val error = errors.firstOrNull { it is ReauthenticationRequired } ?: errors.firstOrNull()
        if (liveOverview && liveTerm && liveMessages) {
            val ui = _ui.value
            val o = (ui.overview as? Load.Ready)?.value ?: return
            val t = (ui.term as? Load.Ready)?.value ?: return
            val a = (ui.messages as? Load.Ready)?.value ?: return
            snapshots.write(SnapshotStore.HOME, HomeSnapshot(o.who, o.next, o.nextUp, t.total, t.completed, a), HomeSnapshot.serializer())
            _ui.update { it.copy(reauthRequired = false, freshness = Freshness.CURRENT, syncedAt = System.currentTimeMillis()) }
        } else when (val e = error) {
            is ReauthenticationRequired -> _ui.update { it.copy(reauthRequired = true, freshness = Freshness.REAUTH_REQUIRED) }
            null -> Unit
            else -> _ui.update { it.copy(reauthRequired = false, freshness = FreshnessClassifier.classify(e, connectivity.isOnline())) }
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
}
